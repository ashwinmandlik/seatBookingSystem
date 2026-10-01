package io.seatreserve.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.seatreserve.IntegrationTest;
import io.seatreserve.Invariants;
import io.seatreserve.reservation.ReservationDeclines.IdempotencyKeyReused;
import io.seatreserve.reservation.ReservationDeclines.PerUserLimitExceeded;
import io.seatreserve.reservation.ReservationDeclines.SeatsUnavailable;
import io.seatreserve.reservation.ReservationService.ReserveResult;
import io.seatreserve.show.CreateShowRequest;
import io.seatreserve.show.SeatCounts;
import io.seatreserve.show.ShowResponse;
import io.seatreserve.show.ShowService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Fires genuinely concurrent requests (all released at once by a start gate)
 * at a real Postgres and checks the correctness bar from the brief.
 */
class ReservationConcurrencyTest extends IntegrationTest {

    @Autowired
    ReservationService service;

    @Autowired
    ShowService shows;

    @Autowired
    JdbcClient jdbc;

    @Test
    void hotSeatHasExactlyOneWinnerAndEveryLoserGetsACleanDecline() throws Exception {
        UUID show = createShow(10, 4);

        List<Outcome> outcomes = race(500, i -> () ->
                service.reserve(new ReserveCommand(show, "user-" + i, List.of("A1"), false, "key-" + i)));

        assertThat(count(outcomes, Outcome.Kind.SUCCESS)).isEqualTo(1);
        assertThat(count(outcomes, Outcome.Kind.SEAT_TAKEN)).isEqualTo(499);
        assertThat(count(outcomes, Outcome.Kind.UNEXPECTED)).isZero();

        String winner = outcomes.stream().filter(o -> o.kind() == Outcome.Kind.SUCCESS)
                .findFirst().orElseThrow().result().reservation().userId();
        assertThat(seatOwner(show, "A1")).isEqualTo(winner);
        assertThat(reservationCount(show)).isEqualTo(1);
        assertReconciles(show, 9, 0, 1);
    }

    @Test
    void perUserLimitHoldsUnderParallelRequestsFromOneUser() throws Exception {
        UUID show = createShow(20, 4);

        // One user, ten parallel single-seat requests for ten different seats.
        List<Outcome> outcomes = race(10, i -> () ->
                service.reserve(new ReserveCommand(show, "greedy", List.of("A" + (i + 1)), false, "k-" + i)));

        assertThat(count(outcomes, Outcome.Kind.SUCCESS)).isEqualTo(4);
        assertThat(count(outcomes, Outcome.Kind.LIMIT)).isEqualTo(6);
        assertThat(count(outcomes, Outcome.Kind.UNEXPECTED)).isZero();
        assertThat(seatsOwnedBy(show, "greedy")).isEqualTo(4);
        assertReconciles(show, 16, 0, 4);
    }

    @Test
    void concurrentRetriesWithTheSameKeyReserveExactlyOnce() throws Exception {
        UUID show = createShow(10, 4);

        List<Outcome> outcomes = race(50, i -> () ->
                service.reserve(new ReserveCommand(show, "retrier", List.of("A1", "A2"), false, "same-key")));

        assertThat(count(outcomes, Outcome.Kind.UNEXPECTED)).isZero();
        assertThat(outcomes).allMatch(o -> o.kind() == Outcome.Kind.SUCCESS);
        assertThat(outcomes.stream().filter(o -> !o.result().replayed())).hasSize(1);
        assertThat(outcomes.stream().map(o -> o.result().reservation().id()).distinct()).hasSize(1);
        assertThat(reservationCount(show)).isEqualTo(1);
        assertReconciles(show, 8, 0, 2);
    }

    @Test
    void sameKeyWithDifferentSeatsIsRejected() {
        UUID show = createShow(10, 4);
        service.reserve(new ReserveCommand(show, "u", List.of("A1"), false, "k"));

        assertThatThrownBy(() -> service.reserve(new ReserveCommand(show, "u", List.of("A2"), false, "k")))
                .isInstanceOf(IdempotencyKeyReused.class);
        assertReconciles(show, 9, 0, 1);
    }

    @Test
    void sameKeyFromAnotherUserIsIndependent() {
        UUID show = createShow(10, 4);
        service.reserve(new ReserveCommand(show, "alice", List.of("A1"), false, "shared-key"));

        ReserveResult bob = service.reserve(new ReserveCommand(show, "bob", List.of("A2"), false, "shared-key"));

        assertThat(bob.replayed()).isFalse();
        assertThat(bob.reservation().userId()).isEqualTo("bob");
    }

    @Test
    void sameKeyOnAnotherShowIsIndependent() {
        UUID first = createShow(10, 4);
        UUID second = createShow(10, 4);
        service.reserve(new ReserveCommand(first, "u", List.of("A1"), false, "k"));

        ReserveResult other = service.reserve(new ReserveCommand(second, "u", List.of("A1"), false, "k"));

        assertThat(other.replayed()).isFalse();
        assertThat(other.reservation().showId()).isEqualTo(second);
    }

    @Test
    void aDeclinedRequestLeavesNoTrace() {
        UUID show = createShow(10, 4);
        service.reserve(new ReserveCommand(show, "alice", List.of("A2"), false, null));

        // Bob wants A1+A2; A2 is taken, so all-or-nothing takes neither...
        assertThatThrownBy(() -> service.reserve(new ReserveCommand(show, "bob", List.of("A1", "A2"), false, "k")))
                .isInstanceOf(SeatsUnavailable.class);
        assertThat(seatOwner(show, "A1")).isNull();
        // ...and his quota and idempotency key were rolled back too.
        assertThat(seatsOwnedBy(show, "bob")).isZero();
        ReserveResult retry = service.reserve(new ReserveCommand(show, "bob", List.of("A1"), false, "k"));
        assertThat(retry.replayed()).isFalse();
        assertReconciles(show, 8, 0, 2);
    }

    @Test
    void overlappingMultiSeatRequestsNeverDeadlockOrHalfSell() throws Exception {
        UUID show = createShow(6, 4);

        // 300 users each grab a random pair from 6 seats, listed in random order:
        // the worst case for lock-ordering deadlocks.
        List<Outcome> outcomes = race(300, i -> {
            List<String> pair = new ArrayList<>(List.of("A1", "A2", "A3", "A4", "A5", "A6"));
            Collections.shuffle(pair, ThreadLocalRandom.current());
            List<String> wanted = pair.subList(0, 2);
            return () -> service.reserve(new ReserveCommand(show, "user-" + i, wanted, false, null));
        });

        assertThat(count(outcomes, Outcome.Kind.UNEXPECTED)).isZero();
        int winners = count(outcomes, Outcome.Kind.SUCCESS);
        assertThat(winners).isBetween(1, 3);
        // All-or-nothing: every confirmed seat belongs to a reservation that got both its seats.
        for (Outcome o : outcomes) {
            if (o.kind() == Outcome.Kind.SUCCESS) {
                Reservation r = o.result().reservation();
                r.seats().forEach(label -> assertThat(seatOwner(show, label)).isEqualTo(r.userId()));
            }
        }
        assertReconciles(show, 6 - 2 * winners, 0, 2 * winners);
    }

    @Test
    void holdModeSetsADeadlineOnEverySeat() {
        UUID show = createShow(10, 4);

        Reservation held = service.reserve(new ReserveCommand(show, "u", List.of("A1", "A2"), true, null))
                .reservation();

        assertThat(held.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(held.expiresAt()).isNotNull();
        List<java.sql.Timestamp> deadlines = jdbc.sql("SELECT held_until FROM seats WHERE show_id = ? AND label IN ('A1','A2')")
                .param(show).query(java.sql.Timestamp.class).list();
        assertThat(deadlines).allMatch(d -> d.toInstant().equals(held.expiresAt()));
        assertReconciles(show, 8, 2, 0);
    }

    @Test
    void amountIsPriceTimesSeatsInPaise() {
        UUID show = createShow(10, 4);
        Reservation r = service.reserve(new ReserveCommand(show, "u", List.of("A1", "A2", "A3"), false, null))
                .reservation();
        assertThat(r.amountPaise()).isEqualTo(3 * 25_000L);
    }

    // ---------------------------------------------------------------- helpers

    record Outcome(Kind kind, ReserveResult result, Throwable error) {
        enum Kind { SUCCESS, SEAT_TAKEN, LIMIT, KEY_REUSED, UNEXPECTED }
    }

    /** Runs n tasks on virtual threads, releasing them all at the same instant. */
    private List<Outcome> race(int n, IntFunction<Callable<ReserveResult>> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ReserveResult>> futures = IntStream.range(0, n).mapToObj(i -> {
                Callable<ReserveResult> body = task.apply(i);
                return pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return body.call();
                });
            }).toList();
            ready.await();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<ReserveResult> f : futures) {
                try {
                    outcomes.add(new Outcome(Outcome.Kind.SUCCESS, f.get(), null));
                } catch (ExecutionException e) {
                    outcomes.add(new Outcome(classify(e.getCause()), null, e.getCause()));
                }
            }
            outcomes.stream().filter(o -> o.kind() == Outcome.Kind.UNEXPECTED).findFirst()
                    .ifPresent(o -> o.error().printStackTrace());
            return outcomes;
        }
    }

    private static Outcome.Kind classify(Throwable t) {
        if (t instanceof SeatsUnavailable) return Outcome.Kind.SEAT_TAKEN;
        if (t instanceof PerUserLimitExceeded) return Outcome.Kind.LIMIT;
        if (t instanceof IdempotencyKeyReused) return Outcome.Kind.KEY_REUSED;
        return Outcome.Kind.UNEXPECTED;
    }

    private static int count(List<Outcome> outcomes, Outcome.Kind kind) {
        return (int) outcomes.stream().filter(o -> o.kind() == kind).count();
    }

    private UUID createShow(int seats, int limit) {
        List<String> labels = IntStream.rangeClosed(1, seats).mapToObj(i -> "A" + i).toList();
        ShowResponse show = shows.create(new CreateShowRequest("test-show", labels, 25_000L, limit));
        return show.id();
    }

    private String seatOwner(UUID show, String label) {
        return jdbc.sql("SELECT user_id FROM seats WHERE show_id = ? AND label = ?")
                .params(show, label).query(String.class).optional().orElse(null);
    }

    private int seatsOwnedBy(UUID show, String user) {
        return jdbc.sql("SELECT count(*) FROM seats WHERE show_id = ? AND user_id = ?")
                .params(show, user).query(Integer.class).single();
    }

    private int reservationCount(UUID show) {
        return jdbc.sql("SELECT count(*) FROM reservations WHERE show_id = ?")
                .param(show).query(Integer.class).single();
    }

    private void assertReconciles(UUID show, int available, int held, int confirmed) {
        SeatCounts counts = shows.get(show).counts();
        assertThat(counts).isEqualTo(new SeatCounts(available, held, confirmed, available + held + confirmed));
        Invariants.assertConsistent(jdbc, show);
    }
}
