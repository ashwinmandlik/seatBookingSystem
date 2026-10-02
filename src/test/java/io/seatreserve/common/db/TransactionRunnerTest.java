package io.seatreserve.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.seatreserve.reservation.service.ReservationDeclines.SeatsUnavailable;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class TransactionRunnerTest {

    private final TransactionRunner runner = new TransactionRunner(new TransactionTemplate(new NoOpTransactions()),
            new SimpleMeterRegistry());

    @Test
    void retriesADeadlockVictimUntilItSucceeds() {
        AtomicInteger calls = new AtomicInteger();

        String result = runner.inTransaction(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new PessimisticLockingFailureException("deadlock detected");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> runner.inTransaction(() -> {
            calls.incrementAndGet();
            throw new PessimisticLockingFailureException("deadlock detected");
        })).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(calls).hasValue(TransactionRunner.MAX_ATTEMPTS);
    }

    @Test
    void neverRetriesADomainDecline() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> runner.inTransaction(() -> {
            calls.incrementAndGet();
            throw new SeatsUnavailable(List.of("A1"), Map.of(), false);
        })).isInstanceOf(SeatsUnavailable.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void neverRetriesPoolExhaustion() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> runner.inTransaction(() -> {
            calls.incrementAndGet();
            throw new CannotGetJdbcConnectionException("pool exhausted", new SQLException("timeout"));
        })).isInstanceOf(CannotGetJdbcConnectionException.class);
        assertThat(calls).hasValue(1);
    }

    /** Lets the template run without a database. */
    private static class NoOpTransactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
