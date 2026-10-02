package io.seatreserve.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.seatreserve.common.idle.IdleAwareSchedule;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.MultiGauge.Row;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-show seat gauges, computed by the database from the seats table itself,
 * so they always agree with GET /shows/{id}:
 *
 * <pre>
 *   seats_available{show_id}   seats_held{show_id}   seats_confirmed{show_id}   seats_total{show_id}
 *   seats_reconciliation_drift{show_id}   total_seats - (available + held + confirmed); must stay 0
 * </pre>
 *
 * Refreshed on a schedule rather than per scrape, so scraping can never add
 * database load during a burst; values lag by at most the refresh interval.
 * Only shows created in the last 30 days are tracked, which bounds both the
 * query and the number of time series.
 */
@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);

    record ShowSeats(String showId, long total, long available, long held, long confirmed) {
    }

    private final JdbcClient jdbc;
    private final IdleAwareSchedule schedule;
    private final MultiGauge available;
    private final MultiGauge held;
    private final MultiGauge confirmed;
    private final MultiGauge total;
    private final MultiGauge drift;

    public SeatGauges(JdbcClient jdbc, MeterRegistry registry, IdleAwareSchedule schedule) {
        this.jdbc = jdbc;
        this.schedule = schedule;
        this.available = MultiGauge.builder("seats.available").description("Seats available, per show").register(registry);
        this.held = MultiGauge.builder("seats.held").description("Seats held, per show").register(registry);
        this.confirmed = MultiGauge.builder("seats.confirmed").description("Seats confirmed, per show").register(registry);
        this.total = MultiGauge.builder("seats.total").description("Seats in the show").register(registry);
        this.drift = MultiGauge.builder("seats.reconciliation.drift")
                .description("total_seats - (available + held + confirmed); anything but 0 is a bug")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${seatreserve.metrics.seat-gauge-refresh-ms:2000}")
    public void refresh() {
        if (!schedule.gaugesDue()) {
            return;   // idle-aware: nothing changed since the last refresh
        }
        try {
            List<ShowSeats> shows = jdbc.sql("""
                            SELECT s.id::text AS show_id, s.total_seats,
                                   count(*) FILTER (WHERE st.status = 'AVAILABLE') AS available,
                                   count(*) FILTER (WHERE st.status = 'HELD')      AS held,
                                   count(*) FILTER (WHERE st.status = 'CONFIRMED') AS confirmed
                            FROM shows s
                            JOIN seats st ON st.show_id = s.id
                            WHERE s.created_at > now() - interval '30 days'
                            GROUP BY s.id, s.total_seats
                            """)
                    .query((rs, i) -> new ShowSeats(rs.getString("show_id"), rs.getLong("total_seats"),
                            rs.getLong("available"), rs.getLong("held"), rs.getLong("confirmed")))
                    .list();
            available.register(rows(shows, ShowSeats::available), true);
            held.register(rows(shows, ShowSeats::held), true);
            confirmed.register(rows(shows, ShowSeats::confirmed), true);
            total.register(rows(shows, ShowSeats::total), true);
            drift.register(rows(shows, s -> s.total() - (s.available() + s.held() + s.confirmed())), true);
            schedule.gaugesRefreshed();
        } catch (RuntimeException e) {
            // Keep the last values; readiness reports database trouble separately.
            log.warn("Seat gauge refresh failed: {}", e.getMessage());
        }
    }

    private interface Value {
        long of(ShowSeats seats);
    }

    private static List<Row<?>> rows(List<ShowSeats> shows, Value value) {
        return shows.stream()
                .<Row<?>>map(s -> Row.of(Tags.of("show_id", s.showId()), value.of(s)))
                .toList();
    }
}
