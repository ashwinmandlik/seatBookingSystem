package io.seatreserve.common.db;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a transaction and re-runs it if the database fails it for a
 * transient reason (deadlock victim, serialization failure, dropped
 * connection), so a momentary database hiccup does not surface as a 5xx.
 *
 * <p>Retrying is safe because a failed transaction rolled back everything it
 * did, including the idempotency key it claimed. The one ambiguous case is a
 * connection lost during COMMIT: the retry then either replays (when the
 * request carried an idempotency key) or is declined because the seat is
 * already taken by this same user. It can never sell a seat twice.
 *
 * <p>Domain declines are not database errors and are never retried.
 */
@Component
public class TransactionRunner {

    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(TransactionRunner.class);

    private final TransactionTemplate tx;

    public TransactionRunner(TransactionTemplate tx) {
        this.tx = tx;
    }

    public <T> T inTransaction(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> work.get());
            } catch (DataAccessException e) {
                if (!isTransient(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Transient database failure, retrying (attempt {}/{}): {}", attempt, MAX_ATTEMPTS,
                        e.getMostSpecificCause().getMessage());
                backOff(attempt);
            }
        }
    }

    static boolean isTransient(DataAccessException e) {
        if (e instanceof CannotGetJdbcConnectionException) {
            // The pool was exhausted and we already waited the full timeout;
            // retrying would only add to the queue.
            return false;
        }
        return e instanceof PessimisticLockingFailureException   // deadlock victim, serialization failure
                || e instanceof TransientDataAccessResourceException
                || e instanceof RecoverableDataAccessException
                || e instanceof DataAccessResourceFailureException; // connection dropped mid-transaction
    }

    /** Jittered, so transactions that collided once do not collide again in lockstep. */
    private static void backOff(int attempt) {
        long millis = ThreadLocalRandom.current().nextLong(5, 25) * attempt;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", e);
        }
    }
}
