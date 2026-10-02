package io.seatreserve.reservation.api;

import io.seatreserve.reservation.model.Reservation;
import io.seatreserve.reservation.model.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID showId,
        String userId,
        List<String> seats,
        long amountPaise,
        ReservationStatus status,
        Instant expiresAt,
        Instant createdAt) {

    static ReservationResponse of(Reservation r) {
        return new ReservationResponse(r.id(), r.showId(), r.userId(), r.seats(), r.amountPaise(), r.status(),
                r.expiresAt(), r.createdAt());
    }
}
