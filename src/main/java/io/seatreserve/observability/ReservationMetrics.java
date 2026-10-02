package io.seatreserve.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.seatreserve.common.error.DomainException;
import io.seatreserve.common.error.ErrorCode;
import io.seatreserve.reservation.ReservationDeclines.SeatsUnavailable;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Business counters, exported at /actuator/prometheus. Every method is called
 * only after the deciding transaction committed (never inside a retried
 * transaction), so the counters reconcile exactly with the API's state:
 *
 * <pre>
 *   reservations_confirmed_total                 reservations that became confirmed
 *   reservations_held_total                      holds placed
 *   reservations_declined_total{reason,source}   declines; reason is seat-taken, per-user-limit,
 *                                                idempotent-replay, ...; source is cache or database
 *   reservations_cancelled_total
 *   holds_expired_total
 * </pre>
 */
@Component
public class ReservationMetrics {

    public static final String REPLAY = "idempotent-replay";

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final Counter held;
    private final Counter cancelled;
    private final Counter expired;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations that became confirmed (directly or by confirming a hold)")
                .register(registry);
        this.held = Counter.builder("reservations.held").description("Holds placed").register(registry);
        this.cancelled = Counter.builder("reservations.cancelled").description("Reservations cancelled")
                .register(registry);
        this.expired = Counter.builder("holds.expired").description("Holds that expired unconfirmed")
                .register(registry);
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void held() {
        held.increment();
    }

    public void cancelled() {
        cancelled.increment();
    }

    public void expired() {
        expired.increment();
    }

    /** A retry that replayed the original reservation instead of selling again. */
    public void replayed() {
        declined(REPLAY, "database");
    }

    public void declined(DomainException e) {
        String source = e instanceof SeatsUnavailable s && s.fastPath() ? "cache" : "database";
        declined(reason(e.code()), source);
    }

    private void declined(String reason, String source) {
        Counter.builder("reservations.declined")
                .description("Reserve requests turned away, by reason")
                .tag("reason", reason)
                .tag("source", source)
                .register(registry)   // idempotent: returns the existing counter for these tags
                .increment();
    }

    /**
     * One reserve request finished: counts it and records its latency, tagged
     * by outcome (confirmed, held, idempotent-replay, seat-taken, ..., error).
     *
     * <pre>
     *   reservation_requests_total{outcome}
     *   reservation_latency_seconds_bucket{outcome,le}   (histogram: p50/p95/p99)
     * </pre>
     */
    public void request(String outcome, long nanos) {
        Counter.builder("reservation.requests").description("Reserve requests handled, by outcome")
                .tag("outcome", outcome).register(registry).increment();
        Timer.builder("reservation.latency").description("Reserve request latency, by outcome")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** A reserve request failed unexpectedly (would surface as a 5xx): reservation_failures_total. */
    public void failure(Throwable cause) {
        Counter.builder("reservation.failures").description("Reserve requests that failed unexpectedly")
                .tag("exception", cause.getClass().getSimpleName()).register(registry).increment();
    }

    public static String outcome(DomainException e) {
        return reason(e.code());
    }

    /** SEAT_TAKEN -> seat-taken, PER_USER_LIMIT -> per-user-limit, matching the brief's wording. */
    static String reason(ErrorCode code) {
        return code.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
