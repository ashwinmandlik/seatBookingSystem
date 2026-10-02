package io.seatreserve.show.repository;

import io.seatreserve.show.model.SeatStatus;
import io.seatreserve.show.model.SeatView;
import io.seatreserve.show.model.Show;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

    private static final RowMapper<Show> SHOW = (rs, i) -> new Show(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getLong("price_paise"),
            rs.getInt("per_user_limit"),
            rs.getInt("total_seats"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public ShowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Show insert(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
        return jdbc.sql("""
                        INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats)
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING *
                        """)
                .params(id, name, pricePaise, perUserLimit, totalSeats)
                .query(SHOW)
                .single();
    }

    /** Inserts every seat in one round trip, preserving the given order. */
    public void insertSeats(UUID showId, List<String> labels) {
        jdbc.sql("""
                        INSERT INTO seats (show_id, label, position)
                        SELECT ?, t.label, t.ord
                        FROM unnest(?::text[]) WITH ORDINALITY AS t(label, ord)
                        """)
                .params(showId, labels.toArray(String[]::new))
                .update();
    }

    public Optional<Show> findById(UUID id) {
        return jdbc.sql("SELECT * FROM shows WHERE id = ?").param(id).query(SHOW).optional();
    }

    /**
     * Every seat with its status, from a single statement and therefore a
     * single consistent snapshot: counts derived from it always reconcile.
     */
    public List<SeatView> seats(UUID showId) {
        return jdbc.sql("SELECT label, status FROM seats WHERE show_id = ? ORDER BY position")
                .param(showId)
                .query((rs, i) -> new SeatView(rs.getString("label"), SeatStatus.valueOf(rs.getString("status"))))
                .list();
    }
}
