package io.seatreserve.reservation;

import io.seatreserve.config.SeatReserveProperties;
import io.seatreserve.reservation.ReservationDeclines.SeatsUnavailable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps an on-sale stampede for one seat from tying up the database.
 *
 * <p>Without this, 500 requests for A12 each take a pooled connection and then
 * sit waiting on A12's row lock, so a handful of hot seats can exhaust the
 * pool and starve every other request. Two in-memory layers prevent that:
 *
 * <ol>
 *   <li><b>Known-taken cache.</b> After a seat is sold (or a request learns it
 *       is taken), later requests for it are declined in microseconds without
 *       touching the database.</li>
 *   <li><b>Per-seat gate.</b> Requests for the same seat wait their turn in
 *       memory, where waiting costs nothing, instead of holding a database
 *       connection while blocked on a row lock.</li>
 * </ol>
 *
 * <p><b>Correctness never depends on this class.</b> It can only decline,
 * never grant: every success is still decided by the database transaction.
 * A stale entry (e.g. a seat cancelled on another instance) can at worst
 * decline a just-freed seat until the entry's short TTL lapses. Each app
 * instance has its own gate and cache; across instances the database row
 * locks still arbitrate.
 */
@Component
public class HotSeatGate {

    private static final int STRIPES = 8192;
    /** Safety valve against unbounded growth; entries are tiny and short-lived. */
    private static final int MAX_ENTRIES = 500_000;

    private record SeatKey(UUID showId, String label) {
    }

    private record Taken(String ownerId, long expiresAtNanos) {
    }

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
    private final Map<SeatKey, Taken> taken = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final long ttlNanos;
    private final LongSupplier nanoClock;

    @Autowired
    public HotSeatGate(SeatReserveProperties props) {
        this(props.hotSeats().enabled(), props.hotSeats().cacheTtlMillis() * 1_000_000L, System::nanoTime);
    }

    HotSeatGate(boolean enabled, long ttlNanos, LongSupplier nanoClock) {
        this.enabled = enabled;
        this.ttlNanos = ttlNanos;
        this.nanoClock = nanoClock;
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /**
     * Declines immediately if any requested seat is known to be taken by
     * someone else. If the requester owns one of the seats, the database must
     * decide: the request may be an idempotent retry that should replay.
     */
    public void declineIfKnownTaken(UUID showId, List<String> labels, String userId) {
        if (!enabled) {
            return;
        }
        long now = nanoClock.getAsLong();
        List<String> takenByOthers = new ArrayList<>();
        for (String label : labels) {
            Taken t = taken.get(new SeatKey(showId, label));
            if (t == null || t.expiresAtNanos() - now <= 0) {
                continue;
            }
            if (t.ownerId().equals(userId)) {
                return;
            }
            takenByOthers.add(label);
        }
        if (!takenByOthers.isEmpty()) {
            throw new SeatsUnavailable(takenByOthers, Map.of(), true);
        }
    }

    /**
     * Runs {@code work} while holding the in-memory gates for the given seats.
     * Gates are striped and always acquired in ascending stripe order, so two
     * multi-seat requests can never deadlock on them, the same rule as the
     * database's label-ordered row locks.
     */
    public <T> T withSeats(UUID showId, List<String> labels, Supplier<T> work) {
        if (!enabled) {
            return work.get();
        }
        int[] order = stripesFor(showId, labels);
        int acquired = 0;
        try {
            for (int index : order) {
                stripes[index].lock();
                acquired++;
            }
            return work.get();
        } finally {
            for (int i = acquired - 1; i >= 0; i--) {
                stripes[order[i]].unlock();
            }
        }
    }

    /** Records seats as taken. Call only after the deciding transaction committed. */
    public void markTaken(UUID showId, Map<String, String> ownerByLabel) {
        if (!enabled || ownerByLabel.isEmpty()) {
            return;
        }
        if (taken.size() >= MAX_ENTRIES) {
            taken.clear();
        }
        long expiresAt = nanoClock.getAsLong() + ttlNanos;
        ownerByLabel.forEach((label, owner) -> taken.put(new SeatKey(showId, label), new Taken(owner, expiresAt)));
    }

    /** Forgets seats that were released (cancel or expiry) on this instance. */
    public void forget(UUID showId, List<String> labels) {
        if (!enabled) {
            return;
        }
        labels.forEach(label -> taken.remove(new SeatKey(showId, label)));
    }

    @Scheduled(fixedDelay = 10_000)
    void evictExpired() {
        long now = nanoClock.getAsLong();
        taken.values().removeIf(t -> t.expiresAtNanos() - now <= 0);
    }

    int size() {
        return taken.size();
    }

    /** Distinct stripe indexes for the seats, ascending: the gate acquisition order. */
    static int[] stripesFor(UUID showId, List<String> labels) {
        return labels.stream()
                .mapToInt(label -> Math.floorMod(31 * showId.hashCode() + label.hashCode(), STRIPES))
                .distinct()
                .sorted()
                .toArray();
    }
}
