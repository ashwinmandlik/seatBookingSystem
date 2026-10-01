package io.seatreserve.show;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

    private final ShowRepository shows;

    public ShowService(ShowRepository shows) {
        this.shows = shows;
    }

    @Transactional
    public ShowResponse create(CreateShowRequest request) {
        Show show = shows.insert(UUID.randomUUID(), request.name().strip(), request.pricePaise(),
                request.effectivePerUserLimit(), request.seats().size());
        shows.insertSeats(show.id(), request.seats());
        List<SeatView> seats = request.seats().stream()
                .map(label -> new SeatView(label, SeatStatus.AVAILABLE))
                .toList();
        return ShowResponse.of(show, seats);
    }

    public ShowResponse get(UUID showId) {
        Show show = require(showId);
        return ShowResponse.of(show, shows.seats(showId));
    }

    public Show require(UUID showId) {
        return shows.findById(showId).orElseThrow(() -> new ShowNotFoundException(showId));
    }
}
