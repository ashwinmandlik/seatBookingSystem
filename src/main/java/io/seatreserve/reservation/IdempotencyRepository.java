package io.seatreserve.reservation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    public record StoredKey(String requestHash, UUID reservationId) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key for this transaction. Returns false if it already exists.
     *
     * <p>Race-free: if another transaction inserted the same (user, show, key)
     * and has not finished, this INSERT blocks on the primary-key index until it
     * does. If that transaction commits, we get a conflict (-> replay); if it
     * rolls back (e.g. the seat was taken), the key is free and we claim it.
     */
    public boolean claim(String userId, UUID showId, String key, String requestHash, UUID reservationId) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (user_id, show_id, idem_key, request_hash, reservation_id)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, show_id, idem_key) DO NOTHING
                        """)
                .params(userId, showId, key, requestHash, reservationId)
                .update() == 1;
    }

    public Optional<StoredKey> find(String userId, UUID showId, String key) {
        return jdbc.sql("""
                        SELECT request_hash, reservation_id FROM idempotency_keys
                        WHERE user_id = ? AND show_id = ? AND idem_key = ?
                        """)
                .params(userId, showId, key)
                .query((rs, i) -> new StoredKey(rs.getString("request_hash"), rs.getObject("reservation_id", UUID.class)))
                .optional();
    }
}
