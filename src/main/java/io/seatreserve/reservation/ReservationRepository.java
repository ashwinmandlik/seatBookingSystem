package io.seatreserve.reservation;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    static final RowMapper<Reservation> RESERVATION = (rs, i) -> new Reservation(
            rs.getObject("id", UUID.class),
            rs.getObject("show_id", UUID.class),
            rs.getString("user_id"),
            strings(rs.getArray("seats")),
            rs.getLong("amount_paise"),
            ReservationStatus.valueOf(rs.getString("status")),
            instantOrNull(rs.getTimestamp("expires_at")),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A newly created reservation and how many seats the same statement assigned to it. */
    public record Created(Reservation reservation, int seatsAssigned) {
    }

    /**
     * Assigns the (already locked) seats and records the reservation in a
     * single statement, i.e. one database round trip instead of two.
     *
     * <p>The seat UPDATE keeps its {@code status = 'AVAILABLE'} guard even
     * though the seats are locked, so this statement alone can never overwrite
     * a taken seat; the caller checks {@code seatsAssigned} and rolls back if
     * it falls short.
     *
     * @param holdSeconds null for a confirmed reservation; otherwise the hold
     *                    expires at now() + holdSeconds. now() is fixed for the
     *                    whole transaction, so seats.held_until and
     *                    reservations.expires_at are identical.
     */
    public Created createAssigningSeats(UUID id, UUID showId, String userId, List<String> seats, long amountPaise,
                                        Long holdSeconds) {
        String[] labels = seats.toArray(String[]::new);
        return jdbc.sql("""
                        WITH assigned AS (
                            UPDATE seats
                            SET status = CASE WHEN ?::bigint IS NULL THEN 'CONFIRMED' ELSE 'HELD' END,
                                reservation_id = ?,
                                user_id = ?,
                                held_until = now() + make_interval(secs => ?::bigint),
                                updated_at = now()
                            WHERE show_id = ? AND label = ANY(?::text[]) AND status = 'AVAILABLE'
                            RETURNING 1
                        ), created AS (
                            INSERT INTO reservations (id, show_id, user_id, seats, amount_paise, status, expires_at)
                            VALUES (?, ?, ?, ?::text[], ?,
                                    CASE WHEN ?::bigint IS NULL THEN 'CONFIRMED' ELSE 'HELD' END,
                                    now() + make_interval(secs => ?::bigint))
                            RETURNING *
                        )
                        SELECT created.*, (SELECT count(*) FROM assigned) AS seats_assigned FROM created
                        """)
                .params(holdSeconds, id, userId, holdSeconds, showId, labels,
                        id, showId, userId, labels, amountPaise, holdSeconds, holdSeconds)
                .query((rs, i) -> new Created(RESERVATION.mapRow(rs, i), rs.getInt("seats_assigned")))
                .single();
    }

    public Optional<Reservation> findById(UUID id) {
        return jdbc.sql("SELECT * FROM reservations WHERE id = ?").param(id).query(RESERVATION).optional();
    }

    /** First lock in every lifecycle transaction (confirm, cancel). */
    public Optional<Reservation> lockById(UUID id) {
        return jdbc.sql("SELECT * FROM reservations WHERE id = ? FOR UPDATE").param(id).query(RESERVATION).optional();
    }

    /**
     * Claims the oldest expired hold for this transaction. SKIP LOCKED lets any
     * number of sweepers (one per app instance) run at once: each skips holds
     * another sweeper, a confirm or a cancel is working on, so no hold is
     * processed twice and nobody waits.
     */
    public Optional<Reservation> lockNextExpired() {
        return jdbc.sql("""
                        SELECT * FROM reservations
                        WHERE status = 'HELD' AND expires_at <= now()
                        ORDER BY expires_at
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """)
                .query(RESERVATION)
                .optional();
    }

    /**
     * HELD -> CONFIRMED, only while the hold is still live. now() is the
     * transaction start, i.e. when the confirm request began, so a request that
     * arrived before the deadline is honoured even if it waited on a lock.
     */
    public Optional<Reservation> confirmIfLive(UUID id) {
        return jdbc.sql("""
                        UPDATE reservations SET status = 'CONFIRMED', updated_at = now()
                        WHERE id = ? AND status = 'HELD' AND expires_at > now()
                        RETURNING *
                        """)
                .param(id)
                .query(RESERVATION)
                .optional();
    }

    /** Earliest deadline among live holds, for the idle-aware sweeper. */
    public Optional<java.time.Instant> earliestHoldDeadline() {
        return jdbc.sql("SELECT min(expires_at) FROM reservations WHERE status = 'HELD'")
                .query((rs, i) -> rs.getTimestamp(1))
                .optional()   // min() over no rows is one NULL row: optional() maps it to empty
                .map(Timestamp::toInstant);
    }

    public Reservation updateStatus(UUID id, ReservationStatus status) {
        return jdbc.sql("UPDATE reservations SET status = ?, updated_at = now() WHERE id = ? RETURNING *")
                .params(status.name(), id)
                .query(RESERVATION)
                .single();
    }

    private static List<String> strings(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }

    private static java.time.Instant instantOrNull(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
