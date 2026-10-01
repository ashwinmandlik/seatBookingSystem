package io.seatreserve.reservation;

import io.seatreserve.common.error.DomainException;
import io.seatreserve.common.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** The clean, expected ways a reservation request can be turned away. */
public final class ReservationDeclines {

    private ReservationDeclines() {
    }

    /** At least one requested seat is held or confirmed by someone (all-or-nothing: nothing was taken). */
    public static class SeatsUnavailable extends DomainException {
        public SeatsUnavailable(List<String> unavailable) {
            super(HttpStatus.CONFLICT, ErrorCode.SEAT_TAKEN, "Seats already taken: " + String.join(", ", unavailable),
                    Map.of("unavailable_seats", unavailable));
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

    public static class UnknownSeats extends DomainException {
        public UnknownSeats(List<String> unknown) {
            super(HttpStatus.BAD_REQUEST, ErrorCode.UNKNOWN_SEATS, "Seats do not exist in this show: "
                    + String.join(", ", unknown), Map.of("unknown_seats", unknown));
        }
    }
}
