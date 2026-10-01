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

    /**
     * @param holdSeconds null for a confirmed reservation; otherwise the hold
     *                    expires at now() + holdSeconds. now() is fixed for the
     *                    whole transaction, so this matches seats.held_until exactly.
     */
    public Reservation insert(UUID id, UUID showId, String userId, List<String> seats, long amountPaise,
                              Long holdSeconds) {
        return jdbc.sql("""
                        INSERT INTO reservations (id, show_id, user_id, seats, amount_paise, status, expires_at)
                        VALUES (?, ?, ?, ?::text[], ?,
                                CASE WHEN ?::bigint IS NULL THEN 'CONFIRMED' ELSE 'HELD' END,
                                now() + make_interval(secs => ?::bigint))
                        RETURNING *
                        """)
                .params(id, showId, userId, seats.toArray(String[]::new), amountPaise, holdSeconds, holdSeconds)
                .query(RESERVATION)
                .single();
    }

    public Optional<Reservation> findById(UUID id) {
        return jdbc.sql("SELECT * FROM reservations WHERE id = ?").param(id).query(RESERVATION).optional();
    }

    private static List<String> strings(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }

    private static java.time.Instant instantOrNull(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
