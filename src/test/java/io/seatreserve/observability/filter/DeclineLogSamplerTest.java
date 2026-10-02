package io.seatreserve.observability.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeclineLogSamplerTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final DeclineLogSampler sampler = new DeclineLogSampler(20, 10, now::get);

    @Test
    void alwaysLogsSuccessesErrorsAndAnythingUnexpected() {
        for (int i = 0; i < 1000; i++) {
            assertThat(sampler.decide(null)).isEqualTo(1);
            assertThat(sampler.decide("INTERNAL_ERROR")).isEqualTo(1);
            assertThat(sampler.decide("UNKNOWN_SEATS")).isEqualTo(1);
        }
    }

    @Test
    void logsEveryDeclineUpToTheBurstThenOneInTen() {
        int logged = 0, marked = 0;
        for (int i = 0; i < 220; i++) {
            int rate = sampler.decide("SEAT_TAKEN");
            if (rate > 0) logged++;
            if (rate == 10) marked++;
        }
        assertThat(logged).isEqualTo(20 + 20);   // first 20, then 200 / 10
        assertThat(marked).isEqualTo(20);
    }

    @Test
    void theBurstAllowanceResetsEverySecond() {
        for (int i = 0; i < 100; i++) {
            sampler.decide("SEAT_TAKEN");
        }
        now.addAndGet(1000);
        assertThat(sampler.decide("SEAT_TAKEN")).isEqualTo(1);
    }
}
