package io.seatreserve.show.model;

import java.time.Instant;
import java.util.UUID;

/** A show is immutable once created, so it can be read without locks. */
public record Show(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Instant createdAt) {
}
