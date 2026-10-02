package io.seatreserve.show.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** The only states a physical seat can be in; persisted in seats.status. */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    CONFIRMED;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
