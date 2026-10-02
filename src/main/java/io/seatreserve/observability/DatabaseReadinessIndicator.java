package io.seatreserve.observability;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * Readiness: can we reach Postgres right now? Reported as "database" in
 * /readyz and /actuator/health.
 *
 * <p>Uses its own single connection, separate from the request pool. During
 * an on-sale burst every pooled connection is busy; a probe that borrowed one
 * would queue for up to the pool timeout, the platform would read that as
 * "database down" and pull a healthy instance out of rotation. With a
 * dedicated connection, readiness measures database reachability, not pool
 * saturation. It fails closed: any error or a 2 s timeout reports DOWN.
 */
@Component("database")
public class DatabaseReadinessIndicator implements HealthIndicator, DisposableBean {

    private static final int TIMEOUT_SECONDS = 2;

    private final HikariDataSource probe;

    public DatabaseReadinessIndicator(DataSourceProperties properties) {
        this.probe = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        probe.setPoolName("readiness-probe");
        probe.setMaximumPoolSize(1);
        probe.setMinimumIdle(0);
        // Close the probe's connection soon after a check, so it never keeps a
        // pause-when-idle database awake.
        probe.setIdleTimeout(30_000);
        probe.setConnectionTimeout(TIMEOUT_SECONDS * 1000L);
        probe.setValidationTimeout(TIMEOUT_SECONDS * 1000L);
        // Never block or fail application startup on the probe itself.
        probe.setInitializationFailTimeout(-1);
    }

    @Override
    public Health health() {
        long started = System.nanoTime();
        try (Connection connection = probe.getConnection(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(TIMEOUT_SECONDS);
            statement.execute("SELECT 1");
            return Health.up()
                    .withDetail("latency_ms", (System.nanoTime() - started) / 1_000_000)
                    .build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }

    @Override
    public void destroy() {
        probe.close();
    }
}
