package io.seatreserve.reservation.api;

import io.seatreserve.show.api.CreateShowRequest;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;

/**
 * Deliberately has no user field: identity comes from the token only, and any
 * extra JSON property (e.g. a spoofed "user_id") is ignored.
 *
 * @param hold true to place a time-boxed hold instead of confirming immediately
 */
public record ReserveRequest(
        @NotEmpty @Size(max = 20) List<@NotNull @Pattern(regexp = CreateShowRequest.SEAT_LABEL) String> seats,
        @Size(min = 1, max = 128) String idempotencyKey,
        Boolean hold) {

    @AssertTrue(message = "seat labels must be unique")
    boolean isSeatsUnique() {
        return seats == null || new HashSet<>(seats).size() == seats.size();
    }
}
