package io.seatreserve.show;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seatreserve.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ShowApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void adminCreatesShowAndEverySeatStartsAvailable() throws Exception {
        String body = """
                {"name": "friday-night", "seats": ["A1", "A2", "A10"], "price_paise": 25000}
                """;
        String created = mvc.perform(post("/shows").header("Authorization", bearer("admin-1", true))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price_paise").value(25000))
                .andExpect(jsonPath("$.per_user_limit").value(4))
                .andExpect(jsonPath("$.counts.available").value(3))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asText();

        mvc.perform(get("/shows/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_seats").value(3))
                .andExpect(jsonPath("$.counts.available").value(3))
                .andExpect(jsonPath("$.counts.held").value(0))
                .andExpect(jsonPath("$.counts.confirmed").value(0))
                .andExpect(jsonPath("$.counts.total").value(3))
                // creation order, not lexical order
                .andExpect(jsonPath("$.seats[2].label").value("A10"))
                .andExpect(jsonPath("$.seats[2].status").value("available"));
    }

    @Test
    void nonAdminCannotCreateShow() throws Exception {
        mvc.perform(post("/shows").header("Authorization", bearer("user-1", false))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":100}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void anonymousCannotCreateShow() throws Exception {
        mvc.perform(post("/shows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":100}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsDuplicateSeatsAndNegativePrice() throws Exception {
        mvc.perform(post("/shows").header("Authorization", bearer("admin-1", true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"seats\":[\"A1\",\"A1\"],\"price_paise\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.price_paise").exists())
                .andExpect(jsonPath("$.error.details.seats_unique").exists());
    }

    @Test
    void unknownShowIs404() throws Exception {
        mvc.perform(get("/shows/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SHOW_NOT_FOUND"));
        mvc.perform(get("/shows/not-a-uuid")).andExpect(status().isNotFound());
    }

    @Test
    void adminTokenRequiresTheAdminKey() throws Exception {
        mvc.perform(post("/auth/token").header("X-Admin-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":\"mallory\"}"))
                .andExpect(status().isForbidden());
    }

    private String bearer(String userId, boolean admin) throws Exception {
        var request = post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"" + userId + "\"}");
        if (admin) {
            request.header("X-Admin-Key", "local-admin-key");
        }
        JsonNode token = json.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return "Bearer " + token.get("access_token").asText();
    }
}
