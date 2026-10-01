package io.seatreserve.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background job that makes holds auto-expire: the database stores each
 * deadline but never acts on it, so something has to.
 *
 * <p>Runs in every app instance with no leader election: {@code SKIP LOCKED}
 * in {@link ReservationRepository#lockNextExpired()} lets instances split the
 * work without ever processing the same hold twice. If every instance is down,
 * the backlog is cleared by whichever starts first.
 */
@Component
@ConditionalOnProperty(name = "seatreserve.sweeper.enabled", havingValue = "true", matchIfMissing = true)
class HoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);

    /** Bounds one tick so a large backlog cannot starve shutdown or the pool. */
    static final int MAX_PER_TICK = 500;

    private final HoldExpiry expiry;

    HoldSweeper(HoldExpiry expiry) {
        this.expiry = expiry;
    }

    @Scheduled(fixedDelayString = "${seatreserve.sweeper.interval-ms:1000}")
    void sweep() {
        int expired = 0;
        try {
            while (expired < MAX_PER_TICK && expiry.expireOne().isPresent()) {
                expired++;
            }
        } catch (RuntimeException e) {
            // Never let one bad tick kill the schedule; the next tick retries.
            log.warn("Hold sweep failed after {} expirations", expired, e);
        }
        if (expired > 0) {
            log.info("Expired {} holds", expired);
        }
    }
}
