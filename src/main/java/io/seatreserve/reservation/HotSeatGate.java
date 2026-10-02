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
 * <p>The known-taken cache has two levels: L1 is this instance's map
 * (nanoseconds), L2 is an optional {@link SharedSeatCache} such as Redis
 * (about a millisecond, shared by every instance). The gate is always local:
 * its whole point is to wait without a network call or a database connection.
 *
 * <p><b>Correctness never depends on this class.</b> It can only decline,
 * never grant: every success is still decided by the database transaction.
 * A stale entry (e.g. a seat cancelled on another instance) can at worst
 * decline a just-freed seat until the entry's short TTL lapses; across
 * instances the database row locks still arbitrate.
 */
@Component
public class HotSeatGate {

    private static final int STRIPES = 8192;
    /** Default safety valve against unbounded growth (~200 bytes per entry). */
    static final int DEFAULT_MAX_ENTRIES = 500_000;

    private record SeatKey(UUID showId, String label) {
    }

    private record Taken(String ownerId, long expiresAtNanos) {
    }

    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
    private final Map<SeatKey, Taken> taken = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final int maxEntries;
    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private final SharedSeatCache shared;

    @Autowired
    public HotSeatGate(SeatReserveProperties props, SharedSeatCache shared) {
        this(props.hotSeats().enabled(), props.hotSeats().cacheTtlMillis() * 1_000_000L, System::nanoTime, shared,
                props.hotSeats().maxEntries());
    }

    HotSeatGate(boolean enabled, long ttlNanos, LongSupplier nanoClock, SharedSeatCache shared) {
        this(enabled, ttlNanos, nanoClock, shared, DEFAULT_MAX_ENTRIES);
    }

    HotSeatGate(boolean enabled, long ttlNanos, LongSupplier nanoClock, SharedSeatCache shared, int maxEntries) {
        this.maxEntries = maxEntries;
        this.enabled = enabled;
        this.ttlNanos = ttlNanos;
        this.nanoClock = nanoClock;
        this.shared = shared;
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /**
     * Declines immediately if any requested seat is known to be taken by
     * someone else, checking this instance's map first and the shared cache
     * only for seats the map does not know. If the requester owns one of the
     * seats, the database must decide: the request may be an idempotent retry
     * that should replay.
     */
    public void declineIfKnownTaken(UUID showId, List<String> labels, String userId) {
        if (!enabled) {
            return;
        }
        Lookup local = lookupLocal(showId, labels, userId);
        if (local.ownedByRequester()) {
            return;
        }
        if (local.takenByOthers().isEmpty() && !local.unknown().isEmpty()) {
            Map<String, String> remote = shared.owners(showId, local.unknown());
            if (remote.containsValue(userId)) {
                return;
            }
            rememberLocally(showId, remote);
            local.takenByOthers().addAll(remote.keySet());
        }
        if (!local.takenByOthers().isEmpty()) {
            throw new SeatsUnavailable(local.takenByOthers().stream().sorted().toList(), Map.of(), true);
        }
    }

    /**
     * Local-only re-check, used after waiting at the gate: the request that held
     * the gate before us wrote its outcome to this instance's map, so another
     * network round trip to the shared cache would add nothing.
     */
    public void declineIfKnownTakenLocally(UUID showId, List<String> labels, String userId) {
        if (!enabled) {
            return;
        }
        Lookup local = lookupLocal(showId, labels, userId);
        if (!local.ownedByRequester() && !local.takenByOthers().isEmpty()) {
            throw new SeatsUnavailable(local.takenByOthers(), Map.of(), true);
        }
    }

    private record Lookup(boolean ownedByRequester, List<String> takenByOthers, List<String> unknown) {
    }

    private Lookup lookupLocal(UUID showId, List<String> labels, String userId) {
        long now = nanoClock.getAsLong();
        List<String> takenByOthers = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String label : labels) {
            Taken t = taken.get(new SeatKey(showId, label));
            if (t == null || t.expiresAtNanos() - now <= 0) {
                unknown.add(label);
            } else if (t.ownerId().equals(userId)) {
                return new Lookup(true, List.of(), List.of());
            } else {
                takenByOthers.add(label);
            }
        }
        return new Lookup(false, takenByOthers, unknown);
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

    /** Records seats as taken, locally and shared. Call only after the deciding transaction committed. */
    public void markTaken(UUID showId, Map<String, String> ownerByLabel) {
        if (!enabled || ownerByLabel.isEmpty()) {
            return;
        }
        rememberLocally(showId, ownerByLabel);
        shared.markTaken(showId, ownerByLabel);
    }

    /** Forgets released seats (cancel or expiry), here and, via the shared cache, on every instance. */
    public void forget(UUID showId, List<String> labels) {
        if (!enabled) {
            return;
        }
        labels.forEach(label -> taken.remove(new SeatKey(showId, label)));
        shared.forget(showId, labels);
    }

    private void rememberLocally(UUID showId, Map<String, String> ownerByLabel) {
        if (ownerByLabel.isEmpty()) {
            return;
        }
        if (taken.size() >= maxEntries) {
            taken.clear();
        }
        long expiresAt = nanoClock.getAsLong() + ttlNanos;
        ownerByLabel.forEach((label, owner) -> taken.put(new SeatKey(showId, label), new Taken(owner, expiresAt)));
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
