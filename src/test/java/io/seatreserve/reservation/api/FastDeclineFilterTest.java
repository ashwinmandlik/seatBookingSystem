package io.seatreserve.reservation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.seatreserve.auth.FastJwtVerifier;
import io.seatreserve.config.SeatReserveProperties.HotSeats;
import io.seatreserve.config.SeatReserveProperties.IdleAware;
import io.seatreserve.config.SeatReserveProperties.SharedCache;
import io.seatreserve.config.SeatReserveProperties;
import io.seatreserve.observability.metrics.ReservationMetrics;
import io.seatreserve.reservation.cache.HotSeatGate;
import io.seatreserve.reservation.cache.SharedSeatCache;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class FastDeclineFilterTest {

    private static final String SECRET = "test-secret-0123456789abcdef-0123456789abcdef";

    private final ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final HotSeatGate gate = new HotSeatGate(true, 60_000_000_000L, System::nanoTime, SharedSeatCache.NONE);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FastDeclineFilter filter = new FastDeclineFilter(true,
            new FastJwtVerifier(props(), json), gate, new ReservationMetrics(registry), json);
    private final UUID show = UUID.randomUUID();

    @Test
    void declinesAKnownTakenSeatWithoutRunningTheRestOfTheStack() throws Exception {
        gate.markTaken(show, Map.of("A12", "alice"));

        Outcome o = run(token("bob"), "{\"seats\":[\"A12\"],\"idempotency_key\":\"k\"}");

        assertThat(o.chainCalled()).isFalse();
        assertThat(o.response().getStatus()).isEqualTo(409);
        JsonNode body = json.readTree(o.response().getContentAsString());
        assertThat(body.at("/error/code").asText()).isEqualTo("SEAT_TAKEN");
        assertThat(body.at("/error/details/unavailable_seats/0").asText()).isEqualTo("A12");
        assertThat(registry.find("reservations.declined").tag("reason", "seat-taken").tag("source", "cache")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void passesEverythingUncertainThroughWithTheBodyIntact() throws Exception {
        gate.markTaken(show, Map.of("A12", "alice"));
        String body = "{\"seats\":[\"A12\"]}";

        // the owner (may be an idempotent retry), a free seat, a bad token, no token, odd bodies
        for (Outcome o : new Outcome[] {
                run(token("alice"), body),
                run(token("bob"), "{\"seats\":[\"A13\"]}"),
                run("Bearer not.a.jwt", body),
                run(null, body),
                run(token("bob"), "{\"seats\":[\"A12\",\"A12\"]}"),
                run(token("bob"), "not json"),
                run(token("bob"), "{\"seats\":[]}")}) {
            assertThat(o.chainCalled()).isTrue();
            assertThat(o.bodySeenDownstream()).isEqualTo(o.bodySent());
        }
    }

    @Test
    void aMultiSeatRequestIsDeclinedOnlyIfEverySeatIsKnownTaken() throws Exception {
        gate.markTaken(show, Map.of("A1", "alice", "A2", "carol"));

        assertThat(run(token("bob"), "{\"seats\":[\"A1\",\"A2\"]}").chainCalled()).isFalse();
        // HotSeatGate declines when any seat is taken by someone else: the full path would also 409 (all-or-nothing)
        assertThat(run(token("bob"), "{\"seats\":[\"A1\",\"A3\"]}").chainCalled()).isFalse();
        // but if the requester owns one of them, only the database can decide
        assertThat(run(token("alice"), "{\"seats\":[\"A1\",\"A2\"]}").chainCalled()).isTrue();
    }

    record Outcome(boolean chainCalled, MockHttpServletResponse response, String bodySent, String bodySeenDownstream) {
    }

    private Outcome run(String authorization, String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shows/" + show + "/reserve");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        filter.doFilter(request, response, chain);
        return new Outcome(seen.get() != null, response, body, seen.get());
    }

    private static String token(String sub) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(sub).issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private static SeatReserveProperties props() {
        return new SeatReserveProperties(300, SECRET, "admin",
                new HotSeats(true, 2000, new SharedCache(false, 5000, 5000), 500_000), new IdleAware(false, 60));
    }
}
