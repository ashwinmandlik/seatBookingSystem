package io.seatreserve.show.model;

import java.util.List;

/**
 * The reconciliation invariant: available + held + confirmed == total. It holds
 * by construction, because every seat row has exactly one status and all
 * counts come from the same snapshot.
 */
public record SeatCounts(int available, int held, int confirmed, int total) {

    public static SeatCounts of(List<SeatView> seats) {
        int available = 0, held = 0, confirmed = 0;
        for (SeatView seat : seats) {
            switch (seat.status()) {
                case AVAILABLE -> available++;
                case HELD -> held++;
                case CONFIRMED -> confirmed++;
            }
        }
        return new SeatCounts(available, held, confirmed, seats.size());
    }
}
