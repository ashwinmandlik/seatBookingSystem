package io.seatreserve.common.idle;

import io.seatreserve.config.SeatReserveProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides when background jobs (hold sweeper, seat gauges) actually need the
 * database, so an idle service leaves it alone.
 *
 * <p>Disabled by default: every job runs on its fixed schedule, exactly as
 * before. Enable it on serverless databases that pause when idle and bill
 * for awake time (e.g. Neon's free tier): a sweeper querying every second
 * would keep such a database awake around the clock.
 *
 * <p>When enabled:
 * <ul>
 *   <li>the sweeper queries only once the earliest known hold deadline has
 *       passed, plus a safety sweep at least every {@code maxIdle};</li>
 *   <li>seat gauges refresh only after seats changed, plus at least every
 *       {@code maxIdle}.</li>
 * </ul>
 * This instance learns about every hold it creates, so it is meant for a
 * single instance; with several, the safety sweep still bounds how late a
 * hold created elsewhere can expire.
 */
@Component
public class IdleAwareSchedule {

    private final boolean enabled;
    private final Duration maxIdle;
    private final Clock clock;
    private final AtomicReference<Instant> nextSweep;
    private volatile boolean seatsChanged = true;
    private volatile Instant lastGaugeRefresh = Instant.MIN;

    @Autowired
    public IdleAwareSchedule(SeatReserveProperties props) {
        this(props.idleAware().enabled(), Duration.ofMinutes(props.idleAware().maxIdleMinutes()), Clock.systemUTC());
    }

    public IdleAwareSchedule(boolean enabled, Duration maxIdle, Clock clock) {
        this.enabled = enabled;
        this.maxIdle = maxIdle;
        this.clock = clock;
        this.nextSweep = new AtomicReference<>(Instant.MIN);   // sweep once at startup
    }

    public boolean enabled() {
        return enabled;
    }

    // ------------------------------------------------------------------ sweeper

    public boolean sweepDue() {
        return !enabled || !clock.instant().isBefore(nextSweep.get());
    }

    /** A hold was created: make sure the sweeper wakes up by its deadline. */
    public void holdCreated(Instant expiresAt) {
        if (enabled && expiresAt != null) {
            nextSweep.accumulateAndGet(expiresAt, (current, candidate) -> candidate.isBefore(current) ? candidate : current);
        }
    }

    /** A sweep finished; {@code earliestHold} is the earliest deadline still pending, or null if none. */
    public void swept(Instant earliestHold) {
        Instant safety = clock.instant().plus(maxIdle);
        Instant next = earliestHold != null && earliestHold.isBefore(safety) ? earliestHold : safety;
        // A hold created during the sweep may already have pulled the deadline earlier; keep the earlier one.
        nextSweep.updateAndGet(current -> current.isAfter(clock.instant()) && current.isBefore(next) ? current : next);
    }

    // ------------------------------------------------------------------- gauges

    public boolean gaugesDue() {
        return !enabled || seatsChanged || !clock.instant().isBefore(lastGaugeRefresh.plus(maxIdle));
    }

    /** Seats changed (reserve, confirm, cancel, expiry, new show): gauges are stale. */
    public void seatsChanged() {
        seatsChanged = true;
    }

    public void gaugesRefreshed() {
        seatsChanged = false;
        lastGaugeRefresh = clock.instant();
    }
}
