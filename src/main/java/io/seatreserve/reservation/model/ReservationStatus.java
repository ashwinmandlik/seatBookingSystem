package io.seatreserve.reservation.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Lifecycle: HELD -> CONFIRMED | CANCELLED | EXPIRED, CONFIRMED -> CANCELLED.
 * A reservation created without a hold starts directly in CONFIRMED.
 */
public enum ReservationStatus {
    HELD,
    CONFIRMED,
    CANCELLED,
    EXPIRED;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
