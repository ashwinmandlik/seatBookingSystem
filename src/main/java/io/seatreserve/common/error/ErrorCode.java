package io.seatreserve.common.error;

/** Stable error codes clients can branch on. Never rename one once published. */
public enum ErrorCode {
    VALIDATION_FAILED,
    MALFORMED_REQUEST,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    SHOW_NOT_FOUND,
    UNKNOWN_SEATS,
    SEAT_TAKEN,
    PER_USER_LIMIT,
    IDEMPOTENCY_KEY_REUSED,
    RESERVATION_NOT_FOUND,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
