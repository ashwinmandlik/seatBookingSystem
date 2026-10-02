package io.seatreserve.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import io.seatreserve.IntegrationTest;
import io.seatreserve.Invariants;
import io.seatreserve.common.error.DomainException;
import io.seatreserve.reservation.model.Reservation;
import io.seatreserve.reservation.model.ReservationStatus;
import io.seatreserve.reservation.service.ReservationDeclines.SeatsUnavailable;
import io.seatreserve.reservation.service.ReservationService.ReserveResult;
import io.seatreserve.reservation.service.ReservationService;
import io.seatreserve.reservation.service.ReserveCommand;
import io.seatreserve.show.api.CreateShowRequest;
import io.seatreserve.show.model.SeatStatus;
import io.seatreserve.show.service.ShowService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The brief's races at the brief's scale: 1000 concurrent requests, plus cancel racing reserve on one seat. */
class ThousandWayRaceTest extends IntegrationTest {

    @Autowired
    ReservationService service;

    @Autowired
    ShowService shows;

    @Autowired
    JdbcClient jdbc;

    @Test
    void thousandUsersOnOneSeatGiveOneWinnerAndNineHundredNinetyNineCleanDeclines() throws Exception {
        UUID show = createShow(20);

        Outcomes o = race(1000, i -> () ->
                service.reserve(new ReserveCommand(show, "user-" + i, List.of("A12"), false, "key-" + i)));

        assertThat(o.successes()).hasSize(1);
        assertThat(o.declines()).hasSize(999).allMatch(SeatsUnavailable.class::isInstance);
        assertThat(o.unexpected()).isEmpty();
        assertThat(seatOwners(show, "A12")).containsExactly(o.successes().getFirst().reservation().userId());
        Invariants.assertConsistent(jdbc, show);
    }

    @Test
    void thousandIdenticalRequestsWithOneKeyCreateExactlyOneReservation() throws Exception {
        UUID show = createShow(20);

        Outcomes o = race(1000, i -> () ->
                service.reserve(new ReserveCommand(show, "retrier", List.of("A12", "A13"), false, "one-key")));

        assertThat(o.unexpected()).isEmpty();
        assertThat(o.declines()).isEmpty();
        assertThat(o.successes()).hasSize(1000);
        assertThat(o.successes().stream().filter(r -> !r.replayed())).hasSize(1);
        assertThat(o.successes().stream().map(r -> r.reservation().id()).distinct()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM reservations WHERE show_id = ?").param(show)
                .query(Integer.class).single()).isEqualTo(1);
        Invariants.assertConsistent(jdbc, show);
    }

    /**
     * The owner cancels while 20 others try to grab the same seat, 30 rounds on
     * 30 seats. Whatever the interleaving, the seat must end up either free or
     * owned by exactly one new reservation: never lost, never double-owned.
     */
    @Test
    void cancelRacingReserveOnTheSameSeatNeverLosesOrDoubleSellsIt() throws Exception {
        UUID show = createShow(30);
        for (int round = 1; round <= 30; round++) {
            String seat = "A" + round;
            Reservation original = service.reserve(new ReserveCommand(show, "owner-" + round, List.of(seat), false,
                    null)).reservation();
            int r = round;

            Outcomes o = race(21, i -> i == 0
                    ? () -> {
                        service.cancel(original.id(), original.userId());
                        return null;
                    }
                    : () -> service.reserve(new ReserveCommand(show, "rival-" + r + "-" + i, List.of(seat), false, null)));

            assertThat(o.unexpected()).isEmpty();
            assertThat(o.successes().stream().filter(x -> x != null)).hasSizeLessThanOrEqualTo(1);
            assertThat(service.get(original.id(), original.userId()).status()).isEqualTo(ReservationStatus.CANCELLED);
            List<String> owners = seatOwners(show, seat);
            if (owners.isEmpty()) {
                // Nobody won the race: the seat must be free and bookable, not lost.
                assertThat(seatStatus(show, seat)).isEqualTo(SeatStatus.AVAILABLE);
                assertThat(service.reserve(new ReserveCommand(show, "late-" + r, List.of(seat), false, null))
                        .reservation().seats()).containsExactly(seat);
            } else {
                assertThat(owners).hasSize(1).doesNotContain(original.userId());
            }
        }
        Invariants.assertConsistent(jdbc, show);
    }

    // ------------------------------------------------------------------ helpers

    record Outcomes(List<ReserveResult> successes, List<DomainException> declines, List<Throwable> unexpected) {
    }

    private Outcomes race(int n, IntFunction<Callable<ReserveResult>> tasks) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ReserveResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                Callable<ReserveResult> task = tasks.apply(i);
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<ReserveResult> successes = new ArrayList<>();
            List<DomainException> declines = new ArrayList<>();
            List<Throwable> unexpected = new ArrayList<>();
            for (Future<ReserveResult> f : futures) {
                try {
                    ReserveResult result = f.get();
                    if (result != null) {
                        successes.add(result);
                    }
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof DomainException d) {
                        declines.add(d);
                    } else {
                        unexpected.add(e.getCause());
                    }
                }
            }
            unexpected.forEach(Throwable::printStackTrace);
            return new Outcomes(successes, declines, unexpected);
        }
    }

    private UUID createShow(int seats) {
        List<String> labels = IntStream.rangeClosed(1, seats).mapToObj(i -> "A" + i).toList();
        return shows.create(new CreateShowRequest("thousand", labels, 25_000L, 4)).id();
    }

    private List<String> seatOwners(UUID show, String label) {
        return jdbc.sql("SELECT user_id FROM seats WHERE show_id = ? AND label = ? AND user_id IS NOT NULL")
                .params(show, label).query(String.class).list();
    }

    private SeatStatus seatStatus(UUID show, String label) {
        return SeatStatus.valueOf(jdbc.sql("SELECT status FROM seats WHERE show_id = ? AND label = ?")
                .params(show, label).query(String.class).single());
    }
}
