package io.seatreserve.reservation;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class QuotaRepository {

    private final JdbcClient jdbc;

    public QuotaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically adds {@code seats} to the user's count if it stays within the
     * limit. Returns false (and changes nothing) if it would exceed it.
     *
     * <p>Race-free: the upsert takes a row lock on (show, user), so a user's
     * concurrent requests serialize here, and the WHERE guard is re-evaluated
     * against the latest committed count. Ten parallel requests on a limit of
     * four can never all pass. The CHECK (held_count <= seat_limit) constraint
     * backs this up at the schema level.
     */
    public boolean tryAcquire(UUID showId, String userId, int seats, int limit) {
        return jdbc.sql("""
                        INSERT INTO user_show_quota AS q (show_id, user_id, held_count, seat_limit)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (show_id, user_id) DO UPDATE
                            SET held_count = q.held_count + EXCLUDED.held_count
                            WHERE q.held_count + EXCLUDED.held_count <= q.seat_limit
                        """)
                .params(showId, userId, seats, limit)
                .update() == 1;
    }

    /** Returns seats to the user's quota (cancel or expiry). */
    public void release(UUID showId, String userId, int seats) {
        jdbc.sql("UPDATE user_show_quota SET held_count = held_count - ? WHERE show_id = ? AND user_id = ?")
                .params(seats, showId, userId)
                .update();
    }

    public int held(UUID showId, String userId) {
        return jdbc.sql("SELECT held_count FROM user_show_quota WHERE show_id = ? AND user_id = ?")
                .params(showId, userId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }
}
