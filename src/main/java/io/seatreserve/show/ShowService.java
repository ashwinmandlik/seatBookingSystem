package io.seatreserve.show;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

    private final ShowRepository shows;
    private final Map<UUID, Show> cache = new ConcurrentHashMap<>();

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

    /**
     * Shows are immutable once created, so they are cached forever after the
     * first read: one fewer database round trip on every reservation. Unknown
     * ids are not cached, so probing random ids cannot grow the cache.
     */
    public Show require(UUID showId) {
        Show cached = cache.get(showId);
        if (cached != null) {
            return cached;
        }
        Show show = shows.findById(showId).orElseThrow(() -> new ShowNotFoundException(showId));
        cache.putIfAbsent(showId, show);
        return show;
    }
}
