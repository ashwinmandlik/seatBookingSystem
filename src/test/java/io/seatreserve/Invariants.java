package io.seatreserve;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Cross-checks seats, reservations and quotas against each other. Every query
 * returns the rows that violate a rule, so a failure names the culprits.
 */
public final class Invariants {

    private Invariants() {
    }

    public static void assertConsistent(JdbcClient jdbc, UUID show) {
        // available + held + confirmed == total_seats
        assertThat(jdbc.sql("""
                        SELECT s.total_seats = count(st.*) FROM shows s
                        LEFT JOIN seats st ON st.show_id = s.id WHERE s.id = ? GROUP BY s.total_seats
                        """).param(show).query(Boolean.class).single())
                .as("seat rows == total_seats").isTrue();

        // Every taken seat belongs to a live reservation of the same user, in the matching state.
        assertThat(violations(jdbc, show, """
                SELECT st.label FROM seats st
                LEFT JOIN reservations r ON r.id = st.reservation_id
                WHERE st.show_id = ? AND st.status <> 'AVAILABLE'
                  AND (r.id IS NULL OR r.user_id <> st.user_id OR r.status <> st.status
                       OR NOT st.label = ANY(r.seats)
                       OR (st.status = 'HELD' AND st.held_until <> r.expires_at))
                """)).as("seats whose owner reservation disagrees").isEmpty();

        // Every live reservation owns exactly its seats; dead ones own none.
        assertThat(violations(jdbc, show, """
                SELECT r.id::text FROM reservations r
                WHERE r.show_id = ?
                  AND (SELECT count(*) FROM seats st WHERE st.reservation_id = r.id)
                      <> CASE WHEN r.status IN ('HELD', 'CONFIRMED') THEN cardinality(r.seats) ELSE 0 END
                """)).as("reservations owning the wrong number of seats").isEmpty();

        // Each user's quota counter equals the seats they actually hold.
        assertThat(violations(jdbc, show, """
                SELECT coalesce(q.user_id, o.user_id) FROM
                  (SELECT user_id, held_count FROM user_show_quota WHERE show_id = ?) q
                FULL JOIN
                  (SELECT user_id, count(*) AS owned FROM seats
                   WHERE show_id = ? AND user_id IS NOT NULL GROUP BY user_id) o
                ON o.user_id = q.user_id
                WHERE coalesce(q.held_count, 0) <> coalesce(o.owned, 0)
                """, show)).as("users whose quota disagrees with seats owned").isEmpty();
    }

    private static List<String> violations(JdbcClient jdbc, UUID show, String sql, Object... extra) {
        Object[] params = new Object[1 + extra.length];
        params[0] = show;
        System.arraycopy(extra, 0, params, 1, extra.length);
        return jdbc.sql(sql).params(params).query(String.class).list();
    }
}
