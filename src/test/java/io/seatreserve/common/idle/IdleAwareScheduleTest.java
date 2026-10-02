package io.seatreserve.common.idle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class IdleAwareScheduleTest {

    private static final Duration MAX_IDLE = Duration.ofMinutes(60);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T10:00:00Z"));
    private final IdleAwareSchedule schedule = new IdleAwareSchedule(true, MAX_IDLE, clock);

    @Test
    void disabledMeansEveryTickRunsAsBefore() {
        IdleAwareSchedule off = new IdleAwareSchedule(false, MAX_IDLE, clock);
        off.swept(null);
        off.gaugesRefreshed();

        assertThat(off.sweepDue()).isTrue();
        assertThat(off.gaugesDue()).isTrue();
    }

    @Test
    void sweepsOnceAtStartupThenStaysAwayFromAnIdleDatabase() {
        assertThat(schedule.sweepDue()).isTrue();

        schedule.swept(null);                         // no holds pending

        assertThat(schedule.sweepDue()).isFalse();
        clock.advance(Duration.ofMinutes(59));
        assertThat(schedule.sweepDue()).isFalse();
        clock.advance(Duration.ofMinutes(1));         // safety sweep
        assertThat(schedule.sweepDue()).isTrue();
    }

    @Test
    void aNewHoldWakesTheSweeperAtItsDeadline() {
        schedule.swept(null);
        schedule.holdCreated(clock.instant().plusSeconds(300));

        clock.advance(Duration.ofSeconds(299));
        assertThat(schedule.sweepDue()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(schedule.sweepDue()).isTrue();
    }

    @Test
    void theNextSweepFollowsTheEarliestPendingHold() {
        schedule.swept(clock.instant().plusSeconds(90));

        clock.advance(Duration.ofSeconds(89));
        assertThat(schedule.sweepDue()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(schedule.sweepDue()).isTrue();
    }

    @Test
    void aHoldCreatedDuringASweepIsNotForgotten() {
        schedule.swept(null);
        schedule.holdCreated(clock.instant().plusSeconds(30));   // created while the sweep ran
        schedule.swept(clock.instant().plusSeconds(600));        // sweep's own view missed it

        clock.advance(Duration.ofSeconds(30));
        assertThat(schedule.sweepDue()).isTrue();
    }

    @Test
    void gaugesRefreshOnlyAfterChangesOrTheSafetyInterval() {
        assertThat(schedule.gaugesDue()).isTrue();
        schedule.gaugesRefreshed();
        assertThat(schedule.gaugesDue()).isFalse();

        schedule.seatsChanged();
        assertThat(schedule.gaugesDue()).isTrue();
        schedule.gaugesRefreshed();

        clock.advance(MAX_IDLE);
        assertThat(schedule.gaugesDue()).isTrue();
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
