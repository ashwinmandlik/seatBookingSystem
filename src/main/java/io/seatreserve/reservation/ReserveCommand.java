package io.seatreserve.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * A validated reserve request. Seats are kept sorted and de-duplicated: the
 * sort order is also the lock order, and it makes ["A2","A1"] and ["A1","A2"]
 * the same request for idempotency purposes.
 *
 * @param idempotencyKey null when the client sent none (no replay protection)
 */
public record ReserveCommand(UUID showId, String userId, List<String> seats, boolean hold, String idempotencyKey) {

    public ReserveCommand {
        seats = seats.stream().sorted().toList();
        if (seats.isEmpty() || seats.stream().distinct().count() != seats.size()) {
            throw new IllegalArgumentException("seats must be non-empty and unique");
        }
    }

    /** What "the same request" means for an idempotency key: same show, same seats, same mode. */
    public String fingerprint() {
        String canonical = showId + "|" + hold + "|" + String.join(",", seats);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
