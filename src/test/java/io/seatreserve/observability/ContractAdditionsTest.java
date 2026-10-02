package io.seatreserve.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seatreserve.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Conventional probe paths, request ids in error bodies, and the request/latency/failure metrics. */
@AutoConfigureMockMvc
@AutoConfigureObservability
class ContractAdditionsTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void probesAreAlsoServedAtHealthLiveAndHealthReady() throws Exception {
        mvc.perform(get("/health/live")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.database.status").value("UP"));
        // The original paths are unchanged.
        mvc.perform(get("/livez")).andExpect(status().isOk());
        mvc.perform(get("/readyz")).andExpect(status().isOk());
    }

    @Test
    void errorBodiesCarryTheRequestId() throws Exception {
        mvc.perform(get("/shows/00000000-0000-0000-0000-000000000000").header("X-Request-Id", "trace-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SHOW_NOT_FOUND"))
                .andExpect(jsonPath("$.request_id").value("trace-404"));
        mvc.perform(post("/shows/00000000-0000-0000-0000-000000000000/reserve").header("X-Request-Id", "trace-401")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"A1\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.request_id").value("trace-401"));
    }

    @Test
    void requestCountAndLatencyAreRecordedPerOutcome() throws Exception {
        String admin = token("admin", true);
        String show = json.readTree(mvc.perform(post("/shows").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"m\",\"seats\":[\"A1\"],\"price_paise\":100}"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/shows/" + show + "/reserve").header("Authorization", token("u1", false))
                .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"A1\"]}")).andExpect(status().isCreated());
        mvc.perform(post("/shows/" + show + "/reserve").header("Authorization", token("u2", false))
                .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"A1\"]}")).andExpect(status().isConflict());

        String scrape = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertThat(scrape)
                .containsPattern("reservation_requests_total\\{[^}]*outcome=\"confirmed\"")
                .containsPattern("reservation_requests_total\\{[^}]*outcome=\"seat-taken\"")
                .containsPattern("reservation_latency_seconds_bucket\\{[^}]*outcome=\"confirmed\"");
    }

    private String token(String user, boolean admin) throws Exception {
        var request = post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"" + user + "\"}");
        if (admin) {
            request.header("X-Admin-Key", "local-admin-key");
        }
        return "Bearer " + json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString())
                .get("access_token").asText();
    }
}
