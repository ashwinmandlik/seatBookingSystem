package io.seatreserve.reservation;

import io.seatreserve.show.SeatStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Row-level operations on seats: the single source of truth for who owns a seat. */
@Repository
public class SeatInventory {

    public record LockedSeat(String label, SeatStatus status) {
    }

    private final JdbcClient jdbc;

    public SeatInventory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the requested seats, always in ascending label order, and returns
     * their current state. Every transaction that locks several seats uses
     * this same order, so two requests for overlapping seats can only wait on
     * each other, never deadlock.
     *
     * <p>Under READ COMMITTED, a request blocked here re-reads the row once the
     * holder commits, so it sees the seat as already taken. 500 requests for
     * A12 queue on one row lock; exactly one finds it AVAILABLE.
     */
    public List<LockedSeat> lockForUpdate(UUID showId, List<String> labels) {
        return jdbc.sql("""
                        SELECT label, status FROM seats
                        WHERE show_id = ? AND label = ANY(?::text[])
                        ORDER BY label
                        FOR UPDATE
                        """)
                .params(showId, labels.toArray(String[]::new))
                .query((rs, i) -> new LockedSeat(rs.getString("label"), SeatStatus.valueOf(rs.getString("status"))))
                .list();
    }

    /**
     * Assigns seats that are locked by this transaction. The status guard is
     * redundant given the lock, but means this statement alone can never
     * overwrite a taken seat. Returns the number of seats assigned.
     *
     * @param holdSeconds null to confirm directly, otherwise hold until now() + this many seconds
     */
    public int assign(UUID showId, List<String> labels, UUID reservationId, String userId, Long holdSeconds) {
        return jdbc.sql("""
                        UPDATE seats
                        SET status = CASE WHEN ?::bigint IS NULL THEN 'CONFIRMED' ELSE 'HELD' END,
                            reservation_id = ?,
                            user_id = ?,
                            held_until = now() + make_interval(secs => ?::bigint),
                            updated_at = now()
                        WHERE show_id = ? AND label = ANY(?::text[]) AND status = 'AVAILABLE'
                        """)
                .params(holdSeconds, reservationId, userId, holdSeconds, showId, labels.toArray(String[]::new))
                .update();
    }
}
