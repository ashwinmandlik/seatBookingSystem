package io.seatreserve.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import io.seatreserve.IntegrationTest;
import io.seatreserve.Invariants;
import io.seatreserve.common.idle.IdleAwareSchedule;
import io.seatreserve.observability.metrics.SeatGauges;
import io.seatreserve.reservation.expiry.HoldExpiry;
import io.seatreserve.reservation.model.Reservation;
import io.seatreserve.reservation.service.ReservationService;
import io.seatreserve.reservation.service.ReserveCommand;
import io.seatreserve.show.api.CreateShowRequest;
import io.seatreserve.show.service.ShowService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** The service with idle-aware background work enabled, as deployed on a pause-when-idle database. */
@TestPropertySource(properties = {"seatreserve.idle-aware.enabled=true", "seatreserve.sweeper.interval-ms=3600000"})
class IdleAwareModeTest extends IntegrationTest {

    @Autowired
    ReservationService service;

    @Autowired
    ShowService shows;

    @Autowired
    HoldExpiry expiry;

    @Autowired
    IdleAwareSchedule schedule;

    @Autowired
    SeatGauges gauges;

    @Autowired
    JdbcClient jdbc;

    @Test
    void anIdleServiceLeavesTheDatabaseAloneAndWakesForRealWork() {
        UUID show = shows.create(new CreateShowRequest("idle", List.of("A1", "A2"), 100L, 4)).id();
        gauges.refresh();
        assertThat(schedule.gaugesDue()).as("nothing changed since the refresh").isFalse();

        Reservation hold = service.reserve(new ReserveCommand(show, "alice", List.of("A1"), true, null))
                .reservation();
        assertThat(schedule.gaugesDue()).as("a reservation makes gauges stale").isTrue();

        // Pretend the hold's deadline has passed: the schedule must now ask for a sweep.
        jdbc.sql("""
                        WITH r AS (UPDATE reservations SET expires_at = now() - interval '1 second'
                                   WHERE id = ? RETURNING id, expires_at)
                        UPDATE seats SET held_until = r.expires_at FROM r WHERE seats.reservation_id = r.id
                        """).param(hold.id()).update();
        schedule.holdCreated(java.time.Instant.now().minusSeconds(1));
        assertThat(schedule.sweepDue()).isTrue();

        // The sweep itself is unchanged: the hold expires and the seat is free again.
        assertThat(expiry.expireOne()).map(Reservation::id).contains(hold.id());
        schedule.swept(null);
        assertThat(schedule.sweepDue()).as("no holds left: sweeper stays away").isFalse();
        Invariants.assertConsistent(jdbc, show);
    }
}
