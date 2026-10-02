package io.seatreserve.reservation.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** @param expiresAt deadline of a hold; null unless status is HELD */
public record Reservation(
        UUID id,
        UUID showId,
        String userId,
        List<String> seats,
        long amountPaise,
        ReservationStatus status,
        Instant expiresAt,
        Instant createdAt) {
}
