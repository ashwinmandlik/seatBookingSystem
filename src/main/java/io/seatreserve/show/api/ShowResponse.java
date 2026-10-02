package io.seatreserve.show.api;

import io.seatreserve.show.model.SeatCounts;
import io.seatreserve.show.model.SeatView;
import io.seatreserve.show.model.Show;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        SeatCounts counts,
        List<SeatView> seats,
        Instant createdAt) {

    public static ShowResponse of(Show show, List<SeatView> seats) {
        return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                show.totalSeats(), SeatCounts.of(seats), seats, show.createdAt());
    }
}
