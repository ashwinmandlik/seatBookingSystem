package io.seatreserve.observability;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rate-limits access-log lines for expected declines during a burst.
 *
 * <p>Measured on one CPU, a JSON log line costs ~0.6 ms, about as much as the
 * fast decline it records. Successes, errors and anything unexpected are
 * always logged. Expected declines (seat taken, limit reached, key reused)
 * are always logged up to {@code burstPerSecond} per second; beyond that, one
 * in {@code sampleEvery} is logged, marked with its sample rate. Metrics stay
 * exact, so counts and reconciliation never depend on logs.
 */
@Component
public class DeclineLogSampler {

    static final Set<String> SAMPLED_OUTCOMES = Set.of("SEAT_TAKEN", "PER_USER_LIMIT", "IDEMPOTENCY_KEY_REUSED");

    private final int burstPerSecond;
    private final int sampleEvery;
    private final LongSupplier clockMillis;
    private final AtomicLong window = new AtomicLong();
    private final AtomicLong inWindow = new AtomicLong();

    @Autowired
    public DeclineLogSampler(@Value("${seatreserve.logging.decline-burst-per-second:20}") int burstPerSecond,
                             @Value("${seatreserve.logging.decline-sample-every:10}") int sampleEvery) {
        this(burstPerSecond, sampleEvery, System::currentTimeMillis);
    }

    DeclineLogSampler(int burstPerSecond, int sampleEvery, LongSupplier clockMillis) {
        this.burstPerSecond = burstPerSecond;
        this.sampleEvery = Math.max(1, sampleEvery);
        this.clockMillis = clockMillis;
    }

    /**
     * @return 0 to skip the line, 1 to log it as is, or N &gt; 1 to log it as a 1-in-N sample
     */
    public int decide(Object outcome) {
        if (outcome == null || !SAMPLED_OUTCOMES.contains(outcome.toString())) {
            return 1;
        }
        long second = clockMillis.getAsLong() / 1000;
        if (window.get() != second && window.getAndSet(second) != second) {
            inWindow.set(0);
        }
        long n = inWindow.incrementAndGet();
        if (n <= burstPerSecond) {
            return 1;
        }
        return (n - burstPerSecond) % sampleEvery == 0 ? sampleEvery : 0;
    }
}
