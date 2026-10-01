package io.seatreserve.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.seatreserve.reservation.ReservationDeclines.SeatsUnavailable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HotSeatGateTest {

    private static final long TTL = Duration.ofSeconds(2).toNanos();

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final HotSeatGate gate = new HotSeatGate(true, TTL, now::get);
    private final UUID show = UUID.randomUUID();

    @Test
    void declinesSeatsKnownTakenBySomeoneElse() {
        gate.markTaken(show, Map.of("A1", "alice"));

        assertThatThrownBy(() -> gate.declineIfKnownTaken(show, List.of("A1", "A2"), "bob"))
                .isInstanceOfSatisfying(SeatsUnavailable.class, e -> {
                    assertThat(e.fastPath()).isTrue();
                    assertThat(e.details()).containsEntry("unavailable_seats", List.of("A1"));
                });
    }

    @Test
    void letsTheOwnerThroughSoAnIdempotentRetryCanReplay() {
        gate.markTaken(show, Map.of("A1", "alice"));

        assertThatCode(() -> gate.declineIfKnownTaken(show, List.of("A1"), "alice")).doesNotThrowAnyException();
    }

    @Test
    void entriesStopBeingTrustedAfterTheirTtl() {
        gate.markTaken(show, Map.of("A1", "alice"));
        now.addAndGet(TTL + 1);

        assertThatCode(() -> gate.declineIfKnownTaken(show, List.of("A1"), "bob")).doesNotThrowAnyException();
        gate.evictExpired();
        assertThat(gate.size()).isZero();
    }

    @Test
    void forgettingAReleasedSeatLetsRequestsThroughImmediately() {
        gate.markTaken(show, Map.of("A1", "alice"));
        gate.forget(show, List.of("A1"));

        assertThatCode(() -> gate.declineIfKnownTaken(show, List.of("A1"), "bob")).doesNotThrowAnyException();
    }

    @Test
    void entriesAreScopedToTheirShow() {
        gate.markTaken(show, Map.of("A1", "alice"));

        assertThatCode(() -> gate.declineIfKnownTaken(UUID.randomUUID(), List.of("A1"), "bob"))
                .doesNotThrowAnyException();
    }

    @Test
    void disabledGateNeverDeclines() {
        HotSeatGate off = new HotSeatGate(false, TTL, now::get);
        off.markTaken(show, Map.of("A1", "alice"));

        assertThatCode(() -> off.declineIfKnownTaken(show, List.of("A1"), "bob")).doesNotThrowAnyException();
    }

    @Test
    void theGateAdmitsOneRequestPerSeatAtATime() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();

        runMany(200, i -> gate.withSeats(show, List.of("A12"), () -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            Thread.onSpinWait();
            inside.decrementAndGet();
            return null;
        }));

        assertThat(maxInside).hasValue(1);
    }

    @Test
    void overlappingMultiSeatRequestsInRandomOrderNeverDeadlockOnTheGate() throws Exception {
        List<String> seats = List.of("A1", "A2", "A3", "A4", "A5");

        // Would hang (and time out) if stripes were not taken in a global order.
        runMany(500, i -> {
            List<String> pick = new ArrayList<>(seats);
            Collections.shuffle(pick, ThreadLocalRandom.current());
            return gate.withSeats(show, pick.subList(0, 3), () -> {
                Thread.yield();
                return null;
            });
        });
    }

    @Test
    void stripesAreAcquiredInAscendingOrderWithoutDuplicates() {
        int[] order = HotSeatGate.stripesFor(show, List.of("C3", "A1", "B2", "A1"));

        assertThat(order).isSorted().doesNotHaveDuplicates();
    }

    private interface Task {
        Object run(int index) throws Exception;
    }

    private static void runMany(int n, Task task) throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> task.run(index)));
            }
            for (Future<Object> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        }
    }
}
