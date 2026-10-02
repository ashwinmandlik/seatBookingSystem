package io.seatreserve.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.seatreserve.IntegrationTest;
import io.seatreserve.observability.health.DatabaseReadinessIndicator;
import io.seatreserve.observability.metrics.SeatGauges;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Spring Boot disables metric exporters in tests by default; this test needs the real scrape. */
@AutoConfigureMockMvc
@AutoConfigureObservability
class ObservabilityTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    MeterRegistry registry;

    @Autowired
    SeatGauges seatGauges;

    // ----------------------------------------------------------------- health

    @Test
    void livenessAndReadinessProbesAreServedOnTheMainPort() throws Exception {
        mvc.perform(get("/livez")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.database.status").value("UP"));
    }

    @Test
    void readinessFailsClosedWhenTheDatabaseIsUnreachable() {
        DataSourceProperties dead = new DataSourceProperties();
        dead.setUrl("jdbc:postgresql://localhost:1/nowhere?connectTimeout=1");
        dead.setUsername("x");
        dead.setPassword("x");
        DatabaseReadinessIndicator indicator = new DatabaseReadinessIndicator(dead);
        try {
            long started = System.nanoTime();
            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat((System.nanoTime() - started) / 1_000_000).as("probe must answer fast").isLessThan(5_000);
        } finally {
            indicator.destroy();
        }
    }

    // ------------------------------------------------------------ request ids

    @Test
    void everyResponseCarriesARequestIdAndASafeInboundOneIsKept() throws Exception {
        mvc.perform(get("/livez")).andExpect(header().string("X-Request-Id",
                org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
        mvc.perform(get("/livez").header("X-Request-Id", "client-abc.123"))
                .andExpect(header().string("X-Request-Id", "client-abc.123"));
        // A header with a line break is rejected by Spring Security's firewall: a clean 400, never a 500.
        mvc.perform(get("/livez").header("X-Request-Id", "evil\r\ninjected: header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
    }

    // ---------------------------------------------------------------- metrics

    @Test
    void countersReconcileWithWhatTheApiAnswered() throws Exception {
        String show = createShow("[\"A1\",\"A2\",\"A3\",\"A4\",\"A5\",\"A6\"]", 2);
        double confirmed = count("reservations.confirmed");
        double seatTaken = declined("seat-taken");
        double limit = declined("per-user-limit");
        double replay = declined("idempotent-replay");

        mvc.perform(reserve(show, "alice", "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}")).andExpect(status().isCreated());
        mvc.perform(reserve(show, "alice", "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}")).andExpect(status().isOk());
        mvc.perform(reserve(show, "bob", "{\"seats\":[\"A1\"]}")).andExpect(status().isConflict());
        mvc.perform(reserve(show, "alice", "{\"seats\":[\"A2\"]}")).andExpect(status().isCreated());
        mvc.perform(reserve(show, "alice", "{\"seats\":[\"A3\"]}")).andExpect(status().isConflict());

        assertThat(count("reservations.confirmed") - confirmed).isEqualTo(2);
        assertThat(declined("seat-taken") - seatTaken).isEqualTo(1);
        assertThat(declined("per-user-limit") - limit).isEqualTo(1);
        assertThat(declined("idempotent-replay") - replay).isEqualTo(1);
    }

    @Test
    void prometheusScrapeExposesTheBriefsMetricsAndSeatGaugesMatchTheApi() throws Exception {
        String show = createShow("[\"A1\",\"A2\",\"A3\"]", 4);
        mvc.perform(reserve(show, "carol", "{\"seats\":[\"A1\"]}")).andExpect(status().isCreated());
        mvc.perform(reserve(show, "dave", "{\"seats\":[\"A1\"]}")).andExpect(status().isConflict());
        seatGauges.refresh();

        // Scrape several times back to back: the gauges must be present in every scrape, even while
        // the background refresh runs (they used to vanish between a remove and a re-add).
        for (int i = 0; i < 20; i++) {
            assertThat(mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString())
                    .as("scrape %d", i).contains("seats_available{application=\"seat-reserve\",show_id=\"" + show + "\"}");
            seatGauges.refresh();
        }

        String scrape = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(scrape)
                .contains("reservations_confirmed_total")
                .contains("reservations_declined_total{")
                .contains("reason=\"seat-taken\"")
                .contains("seats_available{application=\"seat-reserve\",show_id=\"" + show + "\"} 2.0")
                .contains("seats_confirmed{application=\"seat-reserve\",show_id=\"" + show + "\"} 1.0")
                .contains("seats_reconciliation_drift{application=\"seat-reserve\",show_id=\"" + show + "\"} 0.0")
                .contains("seats_capacity{application=\"seat-reserve\",show_id=\"" + show + "\"} 3.0")
                .contains("seats_held{application=\"seat-reserve\",show_id=\"" + show + "\"} 0.0")
                .contains("hikaricp_connections_pending")
                .contains("http_server_requests_seconds_bucket");

        JsonNode api = json.readTree(mvc.perform(get("/shows/" + show)).andReturn().getResponse().getContentAsString());
        assertThat(api.at("/counts/available").asInt()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private double count(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private double declined(String reason) {
        return registry.find("reservations.declined").tag("reason", reason).counters().stream()
                .mapToDouble(c -> c.count()).sum();
    }

    private String createShow(String seats, int limit) throws Exception {
        String body = mvc.perform(post("/shows").header("Authorization", token("admin", true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"obs\",\"seats\":" + seats + ",\"price_paise\":100,\"per_user_limit\":"
                                + limit + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private MockHttpServletRequestBuilder reserve(String show, String user, String body) throws Exception {
        return post("/shows/" + show + "/reserve").header("Authorization", token(user, false))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String token(String user, boolean admin) throws Exception {
        var request = post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"" + user + "\"}");
        if (admin) {
            request.header("X-Admin-Key", "local-admin-key");
        }
        String body = mvc.perform(request).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("access_token").asText();
    }
}
