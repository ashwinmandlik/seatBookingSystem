package io.seatreserve.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReservationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void everyDeclineReasonIsExportedAtZeroBeforeTheFirstDecline() {
        new ReservationMetrics(registry);

        assertThat(declined("seat-taken", "cache")).isZero();
        for (String reason : List.of("seat-taken", "per-user-limit", "idempotent-replay", "idempotency-key-reused",
                "unknown-seats", "show-not-found")) {
            assertThat(declined(reason, "database")).as(reason).isZero();
        }
    }

    @Test
    void declineCountersCarryOnlyLowCardinalityLabels() {
        new ReservationMetrics(registry);

        assertThat(registry.find("reservations.declined").counters()).isNotEmpty().allSatisfy(counter ->
                assertThat(counter.getId().getTags()).extracting(Tag::getKey).containsExactlyInAnyOrder("reason", "source"));
    }

    private double declined(String reason, String source) {
        Counter counter = registry.find("reservations.declined").tag("reason", reason).tag("source", source).counter();
        assertThat(counter).as("reservations_declined_total{reason=%s,source=%s}", reason, source).isNotNull();
        return counter.count();
    }
}
