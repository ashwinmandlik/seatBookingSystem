package io.seatreserve.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seatreserve.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Logs are JSON (ECS) and every line of a request carries its request id and the token's user id.
 * JSON is the Docker image's format; the test task sets LOG_FORMAT=ecs (see build.gradle).
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class StructuredLogTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void requestLinesAreJsonWithRequestIdUserIdAndOutcome(CapturedOutput output) throws Exception {
        String admin = token("admin", true);
        String showBody = mvc.perform(post("/shows").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"logs\",\"seats\":[\"A1\"],\"price_paise\":100}"))
                .andReturn().getResponse().getContentAsString();
        String show = json.readTree(showBody).get("id").asText();

        mvc.perform(post("/shows/" + show + "/reserve").header("Authorization", token("alice", false))
                        .header("X-Request-Id", "trace-alice-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"A1\"],\"user_id\":\"mallory\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/shows/" + show + "/reserve").header("Authorization", token("bob", false))
                        .header("X-Request-Id", "trace-bob-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seats\":[\"A1\"]}"))
                .andExpect(status().isConflict());

        List<JsonNode> alice = linesFor(output, "trace-alice-1");
        assertThat(alice).as("one access line per request").isNotEmpty();
        assertThat(alice).allSatisfy(line -> assertThat(line.path("user_id").asText()).isEqualTo("alice"));
        assertThat(alice).anySatisfy(line -> {
            assertThat(line.path("log").path("logger").asText()).isEqualTo("access");
            assertThat(line.path("status").asInt()).isEqualTo(201);
            assertThat(line.path("duration_ms").isNumber()).isTrue();
            // the reservation detail rides on the access line rather than a second line
            assertThat(line.path("reservation_id").asText()).isNotBlank();
        });

        assertThat(linesFor(output, "trace-bob-1")).anySatisfy(line -> {
            assertThat(line.path("status").asInt()).isEqualTo(409);
            assertThat(line.path("outcome").asText()).isEqualTo("SEAT_TAKEN");
            assertThat(line.path("user_id").asText()).isEqualTo("bob");
        });
    }

    /** Every JSON log line whose request_id matches. */
    private List<JsonNode> linesFor(CapturedOutput output, String requestId) {
        return output.getOut().lines()
                .filter(line -> line.startsWith("{"))
                .map(line -> {
                    try {
                        return json.readTree(line);
                    } catch (Exception e) {
                        throw new IllegalStateException("Log line is not JSON: " + line, e);
                    }
                })
                .filter(node -> requestId.equals(node.path("request_id").asText()))
                .toList();
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
