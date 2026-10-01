package io.seatreserve.reservation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seatreserve.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ReservationApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    String showId;

    @BeforeEach
    void createShow() throws Exception {
        String body = mvc.perform(post("/shows").header("Authorization", token("admin", true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"api\",\"seats\":[\"A1\",\"A2\",\"A3\"],\"price_paise\":25000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        showId = json.readTree(body).get("id").asText();
    }

    @Test
    void reserveReturnsTheContractShape() throws Exception {
        mvc.perform(reserve("alice", "{\"seats\":[\"A1\"],\"idempotency_key\":\"k1\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.reservation_id").isNotEmpty())
                .andExpect(jsonPath("$.show_id").value(showId))
                .andExpect(jsonPath("$.user_id").value("alice"))
                .andExpect(jsonPath("$.seats[0]").value("A1"))
                .andExpect(jsonPath("$.amount_paise").value(25000))
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.expires_at").doesNotExist());
    }

    @Test
    void spoofedUserIdInBodyIsIgnored() throws Exception {
        mvc.perform(reserve("mallory", "{\"seats\":[\"A1\"],\"user_id\":\"alice\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id").value("mallory"));
    }

    @Test
    void retryReplaysWith200AndDifferentBodyOnSameKeyIs409() throws Exception {
        mvc.perform(reserve("alice", "{\"seats\":[\"A1\"]}").header("Idempotency-Key", "k"))
                .andExpect(status().isCreated());
        mvc.perform(reserve("alice", "{\"seats\":[\"A1\"]}").header("Idempotency-Key", "k"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        mvc.perform(reserve("alice", "{\"seats\":[\"A2\"]}").header("Idempotency-Key", "k"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void takenSeatIsA409WithTheSeatListed() throws Exception {
        mvc.perform(reserve("alice", "{\"seats\":[\"A1\"]}")).andExpect(status().isCreated());
        mvc.perform(reserve("bob", "{\"seats\":[\"A1\",\"A2\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SEAT_TAKEN"))
                .andExpect(jsonPath("$.error.details.unavailable_seats[0]").value("A1"));
    }

    @Test
    void holdReturnsHeldWithExpiry() throws Exception {
        mvc.perform(reserve("alice", "{\"seats\":[\"A1\"],\"hold\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("held"))
                .andExpect(jsonPath("$.expires_at").isNotEmpty());
    }

    @Test
    void holdConfirmCancelOverHttp() throws Exception {
        String id = json.readTree(mvc.perform(reserve("alice", "{\"seats\":[\"A1\"],\"hold\":true}"))
                .andReturn().getResponse().getContentAsString()).get("reservation_id").asText();

        mvc.perform(post("/reservations/" + id + "/confirm").header("Authorization", token("alice", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
        mvc.perform(get("/reservations/" + id).header("Authorization", token("alice", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"));
        mvc.perform(post("/reservations/" + id + "/cancel").header("Authorization", token("alice", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
        mvc.perform(get("/shows/" + showId))
                .andExpect(jsonPath("$.counts.available").value(3));
    }

    @Test
    void someoneElsesReservationLooksNonexistent() throws Exception {
        String id = json.readTree(mvc.perform(reserve("alice", "{\"seats\":[\"A1\"]}"))
                .andReturn().getResponse().getContentAsString()).get("reservation_id").asText();

        mvc.perform(post("/reservations/" + id + "/cancel").header("Authorization", token("mallory", false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESERVATION_NOT_FOUND"));
        mvc.perform(get("/reservations/" + id).header("Authorization", token("mallory", false)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/reservations/" + id + "/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(get("/shows/" + showId)).andExpect(jsonPath("$.counts.confirmed").value(1));
    }

    @Test
    void badRequestsAre4xxNot5xx() throws Exception {
        mvc.perform(reserve("alice", "{\"seats\":[\"Z9\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNKNOWN_SEATS"));
        mvc.perform(reserve("alice", "{\"seats\":[\"A1\",\"A1\"]}")).andExpect(status().isBadRequest());
        mvc.perform(reserve("alice", "{\"seats\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(reserve("alice", "not json")).andExpect(status().isBadRequest());
        mvc.perform(reserve("alice", "{\"seats\":[\"A1\"],\"idempotency_key\":\"a\"}")
                        .header("Idempotency-Key", "b"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/shows/" + showId + "/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":[\"A1\"]}"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reserve(String user, String body)
            throws Exception {
        return post("/shows/" + showId + "/reserve").header("Authorization", token(user, false))
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
