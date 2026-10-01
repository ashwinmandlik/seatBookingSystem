package io.seatreserve.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.seatreserve.IntegrationTest;
import io.seatreserve.Invariants;
import io.seatreserve.common.error.DomainException;
import io.seatreserve.reservation.ReservationDeclines.HoldExpired;
import io.seatreserve.reservation.ReservationDeclines.ReservationNotActive;
import io.seatreserve.reservation.ReservationDeclines.ReservationNotFound;
import io.seatreserve.show.CreateShowRequest;
import io.seatreserve.show.SeatStatus;
import io.seatreserve.show.ShowService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class HoldLifecycleTest extends IntegrationTest {

    @Autowired
    ReservationService service;

    @Autowired
    HoldExpiry expiry;

    @Autowired
    ShowService shows;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    // ------------------------------------------------------------- confirm

    @Test
    void confirmTurnsAHoldIntoASale() {
        UUID show = createShow(10, 4);
        Reservation hold = hold(show, "alice", "A1", "A2");

        Reservation confirmed = service.confirm(hold.id(), "alice");

        assertThat(confirmed.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(seatStatus(show, "A1")).isEqualTo(SeatStatus.CONFIRMED);
        assertThat(seatStatus(show, "A2")).isEqualTo(SeatStatus.CONFIRMED);
        // Confirming again is a harmless no-op, so clients can retry.
        assertThat(service.confirm(hold.id(), "alice").status()).isEqualTo(ReservationStatus.CONFIRMED);
        // A confirmed seat no longer expires.
        expireAll();
        assertThat(seatStatus(show, "A1")).isEqualTo(SeatStatus.CONFIRMED);
        Invariants.assertConsistent(jdbc, show);
    }

    @Test
    void aHoldPastItsDeadlineCannotBeConfirmedEvenBeforeTheSweeperRuns() {
        UUID show = createShow(10, 4);
        Reservation hold = hold(show, "alice", "A1");
        moveDeadline(hold.id(), -1_000);

        assertThatThrownBy(() -> service.confirm(hold.id(), "alice")).isInstanceOf(HoldExpired.class);
        assertThat(seatStatus(show, "A1")).isEqualTo(SeatStatus.HELD); // not swept yet

        expireAll();
        assertThat(seatStatus(show, "A1")).isEqualTo(SeatStatus.AVAILABLE);
        assertThatThrownBy(() -> service.confirm(hold.id(), "alice")).isInstanceOf(HoldExpired.class);
        Invariants.assertConsistent(jdbc, show);
    }

    // -------------------------------------------------------------- cancel

    @Test
    void cancelFreesTheSeatsForSomeoneElseAndReturnsTheQuota() {
        UUID show = createShow(10, 4);
        Reservation r = confirmed(show, "alice", "A1", "A2", "A3", "A4");

        Reservation cancelled = service.cancel(r.id(), "alice");

        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(confirmed(show, "bob", "A1").userId()).isEqualTo("bob");
        // Alice's quota is back to zero: she can take four seats again.
        assertThat(confirmed(show, "alice", "A5", "A6", "A7", "A8").seats()).hasSize(4);
        // Cancelling twice is a no-op.
        assertThat(service.cancel(r.id(), "alice").status()).isEqualTo(ReservationStatus.CANCELLED);
        Invariants.assertConsistent(jdbc, show);
    }

    @Test
    void onlyTheOwnerCanCancelOrConfirmAndOthersCannotEvenSeeIt() {
        UUID show = createShow(10, 4);
        Reservation r = hold(show, "alice", "A1");

        assertThatThrownBy(() -> service.cancel(r.id(), "mallory")).isInstanceOf(ReservationNotFound.class);
        assertThatThrownBy(() -> service.confirm(r.id(), "mallory")).isInstanceOf(ReservationNotFound.class);
        assertThatThrownBy(() -> service.get(r.id(), "mallory")).isInstanceOf(ReservationNotFound.class);
        assertThat(seatOwner(show, "A1")).isEqualTo("alice");
        Invariants.assertConsistent(jdbc, show);
    }

    @Test
    void anExpiredHoldCanNeverResurrectOrReleaseASeatNowSoldToSomeoneElse() {
        UUID show = createShow(10, 4);
        Reservation alices = hold(show, "alice", "A1");
        moveDeadline(alices.id(), -1_000);
        expireAll();
        Reservation bobs = confirmed(show, "bob", "A1");

        assertThatThrownBy(() -> service.cancel(alices.id(), "alice")).isInstanceOf(ReservationNotActive.class);
        assertThatThrownBy(() -> service.confirm(alices.id(), "alice")).isInstanceOf(HoldExpired.class);
        assertThat(expiry.expireOne()).isEmpty();

        assertThat(seatOwner(show, "A1")).isEqualTo("bob");
        assertThat(seatStatus(show, "A1")).isEqualTo(SeatStatus.CONFIRMED);
        assertThat(service.get(bobs.id(), "bob").status()).isEqualTo(ReservationStatus.CONFIRMED);
        Invariants.assertConsistent(jdbc, show);
    }

    // -------------------------------------------------------------- expiry

    @Test
    void expiryFreesSeatsAndReturnsQuota() {
        UUID show = createShow(10, 4);
        Reservation hold = hold(show, "alice", "A1", "A2", "A3", "A4");
        moveDeadline(hold.id(), -1_000);

        Optional<Reservation> expired = expiry.expireOne();

        assertThat(expired).map(Reservation::status).contains(ReservationStatus.EXPIRED);
        assertThat(seatStatus(show, "A1")).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(confirmed(show, "alice", "A5", "A6", "A7", "A8").seats()).hasSize(4);
        Invariants.assertConsistent(jdbc, show);
    }

    @Test
    void liveHoldsAreNotExpired() {
        UUID show = createShow(10, 4);
        hold(show, "alice", "A1");

        assertThat(expiry.expireOne()).isEmpty();
        assertThat(seatStatus(show, "A1")).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void manySweepersInParallelExpireEveryHoldExactlyOnce() throws Exception {
        UUID show = createShow(60, 4);
        List<UUID> holds = IntStream.rangeClosed(1, 60)
                .mapToObj(i -> hold(show, "user-" + i, "A" + i).id()).toList();
        holds.forEach(id -> moveDeadline(id, -1_000));

        // Eight "app instances" sweeping at once.
        List<UUID> expired = new CopyOnWriteArrayList<>();
        runConcurrently(8, i -> {
            Optional<Reservation> next;
            while ((next = expiry.expireOne()).isPresent()) {
                expired.add(next.get().id());
            }
        });

        assertThat(expired).hasSize(60).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(holds);
        assertThat(jdbc.sql("SELECT count(*) FROM seats WHERE show_id = ? AND status = 'AVAILABLE'")
                .param(show).query(Integer.class).single()).isEqualTo(60);
        Invariants.assertConsistent(jdbc, show);
    }

    // --------------------------------------------------------------- races

    @Test
    void cancelRacingTheSweeperReleasesEachHoldExactlyOnce() throws Exception {
        UUID show = createShow(40, 4);
        List<Reservation> holds = IntStream.rangeClosed(1, 40)
                .mapToObj(i -> hold(show, "user-" + i, "A" + i)).toList();
        holds.forEach(h -> moveDeadline(h.id(), -1_000));

        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        // 40 owners cancelling + 4 sweepers, all released at once.
        runConcurrently(44, i -> {
            try {
                if (i < 40) {
                    service.cancel(holds.get(i).id(), holds.get(i).userId());
                } else {
                    expireAll();
                }
            } catch (ReservationNotActive expected) {
                // the sweeper got there first
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        assertThat(unexpected).isEmpty();
        // A double release would drive a quota negative and trip its CHECK constraint;
        // the invariant check also catches any quota/seat disagreement.
        assertThat(statuses(show)).allMatch(s -> s == ReservationStatus.CANCELLED || s == ReservationStatus.EXPIRED);
        Invariants.assertConsistent(jdbc, show);
    }

    @Test
    void confirmRacingTheDeadlineEndsFullyConfirmedOrFullyExpiredNeverHalfway() throws Exception {
        UUID show = createShow(80, 4);
        List<Reservation> holds = IntStream.range(0, 40)
                .mapToObj(i -> hold(show, "user-" + i, "A" + (2 * i + 1), "A" + (2 * i + 2))).toList();
        // Deadlines land within the next 40ms, i.e. while the confirms are in flight.
        holds.forEach(h -> moveDeadline(h.id(), ThreadLocalRandom.current().nextInt(0, 40)));

        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        runConcurrently(44, i -> {
            try {
                if (i < 40) {
                    service.confirm(holds.get(i).id(), holds.get(i).userId());
                } else {
                    long until = System.nanoTime() + 150_000_000L;
                    while (System.nanoTime() < until) {
                        expireAll();
                    }
                }
            } catch (HoldExpired expected) {
                // lost the race against the deadline
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });
        Thread.sleep(50);
        expireAll();

        assertThat(unexpected).isEmpty();
        assertThat(statuses(show)).allMatch(s -> s == ReservationStatus.CONFIRMED || s == ReservationStatus.EXPIRED);
        Invariants.assertConsistent(jdbc, show);
    }

    /**
     * Reserves, holds, confirms, cancels, deadline changes and sweeps, all at
     * once on a small hall. Any lock-order mistake shows up here as a deadlock
     * (an unexpected database exception); any bookkeeping mistake shows up in
     * the invariant check.
     */
    @Test
    void mixedTrafficNeverDeadlocksAndKeepsEveryInvariant() throws Exception {
        UUID show = createShow(10, 4);
        List<String> labels = IntStream.rangeClosed(1, 10).mapToObj(i -> "A" + i).toList();
        List<Reservation> known = new CopyOnWriteArrayList<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        Set<String> outcomes = ConcurrentHashMap.newKeySet();

        runConcurrently(100, worker -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int op = 0; op < 15; op++) {
                try {
                    int kind = rnd.nextInt(10);
                    Reservation target = known.isEmpty() ? null : known.get(rnd.nextInt(known.size()));
                    if (kind < 4 || target == null) {
                        List<String> pick = new ArrayList<>(labels);
                        Collections.shuffle(pick, rnd);
                        String user = "user-" + rnd.nextInt(12);
                        known.add(service.reserve(new ReserveCommand(show, user, pick.subList(0, 1 + rnd.nextInt(2)),
                                rnd.nextBoolean(), null)).reservation());
                        outcomes.add("reserved");
                    } else if (kind < 6) {
                        service.cancel(target.id(), target.userId());
                        outcomes.add("cancelled");
                    } else if (kind < 7) {
                        service.confirm(target.id(), target.userId());
                        outcomes.add("confirmed");
                    } else if (kind < 8) {
                        moveDeadline(target.id(), -1_000);
                    } else {
                        expiry.expireOne().ifPresent(r -> outcomes.add("expired"));
                    }
                } catch (DomainException expected) {
                    // seat taken, limit reached, already expired... all legitimate answers
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }
        });

        unexpected.forEach(Throwable::printStackTrace);
        assertThat(unexpected).isEmpty();
        assertThat(outcomes).contains("reserved", "cancelled", "expired");
        Invariants.assertConsistent(jdbc, show);
        expireAll();
        Invariants.assertConsistent(jdbc, show);
    }

    // ------------------------------------------------------------- helpers

    private interface Task {
        void run(int index) throws Exception;
    }

    /** Runs n tasks on virtual threads, released together by a start gate. */
    private static void runConcurrently(int n, Task task) throws Exception {
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    task.run(index);
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (var f : futures) {
                f.get();
            }
        }
    }

    private void expireAll() {
        while (expiry.expireOne().isPresent()) {
            // drain
        }
    }

    /**
     * Simulates the passage of time by moving a live hold's deadline to now +
     * offset. Locks in the production order (reservation, then seats by label)
     * so the test itself cannot introduce a deadlock.
     */
    private void moveDeadline(UUID reservationId, long offsetMillis) {
        tx.executeWithoutResult(status -> {
            boolean live = jdbc.sql("SELECT status = 'HELD' FROM reservations WHERE id = ? FOR UPDATE")
                    .param(reservationId).query(Boolean.class).single();
            if (!live) {
                return;
            }
            jdbc.sql("SELECT label FROM seats WHERE reservation_id = ? ORDER BY label FOR UPDATE")
                    .param(reservationId).query(String.class).list();
            jdbc.sql("UPDATE reservations SET expires_at = now() + make_interval(secs => ? / 1000.0) WHERE id = ?")
                    .params(offsetMillis, reservationId).update();
            jdbc.sql("""
                            UPDATE seats SET held_until = r.expires_at
                            FROM reservations r WHERE r.id = ? AND seats.reservation_id = r.id
                            """)
                    .param(reservationId).update();
        });
    }

    private UUID createShow(int seats, int limit) {
        List<String> labels = IntStream.rangeClosed(1, seats).mapToObj(i -> "A" + i).toList();
        return shows.create(new CreateShowRequest("lifecycle", labels, 25_000L, limit)).id();
    }

    private Reservation hold(UUID show, String user, String... seats) {
        return service.reserve(new ReserveCommand(show, user, List.of(seats), true, null)).reservation();
    }

    private Reservation confirmed(UUID show, String user, String... seats) {
        return service.reserve(new ReserveCommand(show, user, List.of(seats), false, null)).reservation();
    }

    private SeatStatus seatStatus(UUID show, String label) {
        return SeatStatus.valueOf(jdbc.sql("SELECT status FROM seats WHERE show_id = ? AND label = ?")
                .params(show, label).query(String.class).single());
    }

    private String seatOwner(UUID show, String label) {
        return jdbc.sql("SELECT user_id FROM seats WHERE show_id = ? AND label = ?")
                .params(show, label).query(String.class).optional().orElse(null);
    }

    private List<ReservationStatus> statuses(UUID show) {
        return jdbc.sql("SELECT status FROM reservations WHERE show_id = ?").param(show)
                .query((rs, i) -> ReservationStatus.valueOf(rs.getString(1))).list();
    }
}
