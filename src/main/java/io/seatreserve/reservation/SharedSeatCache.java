package io.seatreserve.reservation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Second-level "seat is taken" cache shared by all app instances (L2 behind
 * {@link HotSeatGate}'s per-instance map).
 *
 * <p>Contract: <b>best effort, never throws.</b> An implementation that cannot
 * reach its backing store must behave like an empty cache. The service is
 * fully correct without it; it only lets instances share what they learned
 * and lets a cancel on one instance clear the entry for all of them.
 */
public interface SharedSeatCache {

    /** Owners of whichever of these seats are known taken (label -> owner). Missing = unknown. */
    Map<String, String> owners(UUID showId, List<String> labels);

    /** Records seats as taken. Call only after the deciding transaction committed. */
    void markTaken(UUID showId, Map<String, String> ownerByLabel);

    /** Removes released seats for every instance. */
    void forget(UUID showId, List<String> labels);

    /** The default: no shared layer at all. */
    SharedSeatCache NONE = new SharedSeatCache() {
        @Override
        public Map<String, String> owners(UUID showId, List<String> labels) {
            return Map.of();
        }

        @Override
        public void markTaken(UUID showId, Map<String, String> ownerByLabel) {
        }

        @Override
        public void forget(UUID showId, List<String> labels) {
        }
    };
}
