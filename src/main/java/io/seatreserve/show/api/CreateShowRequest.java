package io.seatreserve.show.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;

public record CreateShowRequest(
        @NotBlank @Size(max = 200) String name,
        @NotEmpty @Size(max = MAX_SEATS) List<@NotNull @Pattern(regexp = SEAT_LABEL) String> seats,
        @NotNull @PositiveOrZero Long pricePaise,
        @Positive @Max(100) Integer perUserLimit) {

    public static final int MAX_SEATS = 50_000;
    public static final String SEAT_LABEL = "[A-Za-z0-9_-]{1,16}";
    public static final int DEFAULT_PER_USER_LIMIT = 4;

    @AssertTrue(message = "seat labels must be unique")
    boolean isSeatsUnique() {
        return seats == null || new HashSet<>(seats).size() == seats.size();
    }

    public int effectivePerUserLimit() {
        return perUserLimit == null ? DEFAULT_PER_USER_LIMIT : perUserLimit;
    }
}
