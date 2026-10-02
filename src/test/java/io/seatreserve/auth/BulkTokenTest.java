package io.seatreserve.auth;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class BulkTokenTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void mintsNumberedUsersWhoseTokensWorkAsThoseUsers() throws Exception {
        JsonNode body = bulk("{\"count\":3,\"prefix\":\"buyer-\"}");
        assertThat(body.get("count").asInt()).isEqualTo(3);
        assertThat(body.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(body.get("tokens").fieldNames()).toIterable().containsExactly("buyer-1", "buyer-2", "buyer-3");

        String show = createShow();
        for (String user : new String[] {"buyer-1", "buyer-2"}) {
            mvc.perform(post("/shows/" + show + "/reserve")
                            .header("Authorization", "Bearer " + body.at("/tokens/" + user).asText())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"seats\":[\"" + (user.equals("buyer-1") ? "A1" : "A2") + "\"]}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.user_id").value(user));
        }
    }

    @Test
    void acceptsAnExplicitListAndDropsDuplicates() throws Exception {
        JsonNode body = bulk("{\"user_ids\":[\"alice\",\"bob\",\"alice\"]}");

        assertThat(body.get("count").asInt()).isEqualTo(2);
        assertThat(body.get("tokens").fieldNames()).toIterable().containsExactly("alice", "bob");
    }

    @Test
    void twentyThousandTokensInOneCall() throws Exception {
        long started = System.nanoTime();
        JsonNode body = bulk("{\"count\":20000}");
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(body.get("count").asInt()).isEqualTo(20_000);
        assertThat(body.at("/tokens/user-20000").asText()).isNotBlank();
        System.out.println("20000 tokens minted in " + millis + " ms");
    }

    @Test
    void requiresTheAdminKey() throws Exception {
        mvc.perform(post("/auth/tokens").contentType(MediaType.APPLICATION_JSON).content("{\"count\":2}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/auth/tokens").header("X-Admin-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsAmbiguousOrOversizedRequests() throws Exception {
        for (String bad : new String[] {
                "{}",
                "{\"count\":2,\"user_ids\":[\"a\"]}",
                "{\"count\":25001}",
                "{\"count\":0}",
                "{\"user_ids\":[\"has space\"]}"}) {
            mvc.perform(admin(post("/auth/tokens")).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
    }

    private JsonNode bulk(String request) throws Exception {
        return json.readTree(mvc.perform(admin(post("/auth/tokens"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String createShow() throws Exception {
        String admin = json.readTree(mvc.perform(admin(post("/auth/token")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"admin\"}")).andReturn().getResponse().getContentAsString())
                .get("access_token").asText();
        return json.readTree(mvc.perform(post("/shows").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bulk\",\"seats\":[\"A1\",\"A2\"],\"price_paise\":100}"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("X-Admin-Key", "local-admin-key");
    }
}
