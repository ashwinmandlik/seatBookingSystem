package io.seatreserve.reservation;

import io.seatreserve.common.error.DomainException;
import io.seatreserve.common.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** The clean, expected ways a reservation request can be turned away. */
public final class ReservationDeclines {

    private ReservationDeclines() {
    }

    /** At least one requested seat is held or confirmed by someone (all-or-nothing: nothing was taken). */
    public static class SeatsUnavailable extends DomainException {

        private final Map<String, String> owners;
        private final boolean fastPath;

        /**
         * @param owners   label -> owning user, for the hot-seat cache only; never sent to clients
         * @param fastPath true if declined from the in-memory cache without touching the database
         */
        public SeatsUnavailable(List<String> unavailable, Map<String, String> owners, boolean fastPath) {
            super(HttpStatus.CONFLICT, ErrorCode.SEAT_TAKEN, "Seats already taken: " + String.join(", ", unavailable),
                    Map.of("unavailable_seats", unavailable));
            this.owners = Map.copyOf(owners);
            this.fastPath = fastPath;
        }

        public Map<String, String> owners() {
            return owners;
        }

        public boolean fastPath() {
            return fastPath;
        }
    }

    public static class PerUserLimitExceeded extends DomainException {
        public PerUserLimitExceeded(int limit, int alreadyHeld, int requested) {
            super(HttpStatus.CONFLICT, ErrorCode.PER_USER_LIMIT,
                    "Per-user limit of " + limit + " seats for this show would be exceeded",
                    Map.of("limit", limit, "already_held", alreadyHeld, "requested", requested));
        }
    }

    /** Same idempotency key, different request: the key cannot be reused for new work. */
    public static class IdempotencyKeyReused extends DomainException {
        public IdempotencyKeyReused() {
            super(HttpStatus.CONFLICT, ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key was already used with a different request");
        }
    }

    /**
     * Also used when the reservation exists but belongs to someone else: telling
     * a caller "not yours" would confirm that the id exists.
     */
    public static class ReservationNotFound extends DomainException {
        public ReservationNotFound(UUID id) {
            super(HttpStatus.NOT_FOUND, ErrorCode.RESERVATION_NOT_FOUND, "Reservation " + id + " not found");
        }
    }

    /** The reservation is already cancelled or expired; its seats may belong to someone else now. */
    public static class ReservationNotActive extends DomainException {
        public ReservationNotActive(UUID id, ReservationStatus status) {
            super(HttpStatus.CONFLICT, ErrorCode.RESERVATION_NOT_ACTIVE,
                    "Reservation " + id + " is " + status.json(), Map.of("status", status.json()));
        }
    }

    public static class HoldExpired extends DomainException {
        public HoldExpired(UUID id) {
            super(HttpStatus.CONFLICT, ErrorCode.HOLD_EXPIRED, "Hold " + id + " expired before it was confirmed");
        }
    }

    public static class UnknownSeats extends DomainException {
        public UnknownSeats(List<String> unknown) {
            super(HttpStatus.BAD_REQUEST, ErrorCode.UNKNOWN_SEATS, "Seats do not exist in this show: "
                    + String.join(", ", unknown), Map.of("unknown_seats", unknown));
        }
    }
}
