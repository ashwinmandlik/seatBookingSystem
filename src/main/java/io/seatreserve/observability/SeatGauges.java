package io.seatreserve.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.seatreserve.common.idle.IdleAwareSchedule;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.MultiGauge.Row;
import io.micrometer.core.instrument.Tags;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
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
 *   seats_available{show_id}   seats_held{show_id}   seats_confirmed{show_id}   seats_capacity{show_id}
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
        // Not "seats.total": Prometheus reserves the _total suffix for counters, so a gauge with
        // that name is silently exported as plain "seats".
        this.total = MultiGauge.builder("seats.capacity").description("Seats in the show").register(registry);
        this.drift = MultiGauge.builder("seats.reconciliation.drift")
                .description("total_seats - (available + held + confirmed); anything but 0 is a bug")
                .register(registry);
    }

    /**
     * Live values per show. Each show's gauges are registered once, read these
     * holders, and are only updated in place afterwards.
     */
    private static final class Counts {
        final AtomicLong available = new AtomicLong();
        final AtomicLong held = new AtomicLong();
        final AtomicLong confirmed = new AtomicLong();
        final AtomicLong capacity = new AtomicLong();
        final AtomicLong drift = new AtomicLong();
    }

    private final Map<String, Counts> counts = new ConcurrentHashMap<>();

    /**
     * Updates the per-show values in place. Registering with overwrite=true on
     * every refresh (the earlier approach) makes MultiGauge remove and re-add
     * each gauge, so a scrape landing in between saw no seat gauges at all.
     * Rows are now registered once (overwrite=false) and read live holders;
     * shows that leave the window are still removed. Synchronized so two
     * refreshes never interleave.
     */
    @Scheduled(fixedDelayString = "${seatreserve.metrics.seat-gauge-refresh-ms:2000}")
    public synchronized void refresh() {
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
            Set<String> current = new HashSet<>();
            for (ShowSeats s : shows) {
                current.add(s.showId());
                Counts c = counts.computeIfAbsent(s.showId(), id -> new Counts());
                c.available.set(s.available());
                c.held.set(s.held());
                c.confirmed.set(s.confirmed());
                c.capacity.set(s.total());
                c.drift.set(s.total() - (s.available() + s.held() + s.confirmed()));
            }
            counts.keySet().retainAll(current);
            available.register(rows(c -> c.available), false);
            held.register(rows(c -> c.held), false);
            confirmed.register(rows(c -> c.confirmed), false);
            total.register(rows(c -> c.capacity), false);
            drift.register(rows(c -> c.drift), false);
            schedule.gaugesRefreshed();
        } catch (RuntimeException e) {
            // Keep the last values; readiness reports database trouble separately.
            log.warn("Seat gauge refresh failed: {}", e.getMessage());
        }
    }

    private List<Row<?>> rows(Function<Counts, AtomicLong> field) {
        return counts.entrySet().stream()
                .<Row<?>>map(e -> Row.of(Tags.of("show_id", e.getKey()), field.apply(e.getValue()), AtomicLong::doubleValue))
                .toList();
    }
}
