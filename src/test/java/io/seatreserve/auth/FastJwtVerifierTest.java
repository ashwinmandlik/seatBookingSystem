package io.seatreserve.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** The fast verifier must accept exactly the tokens the real pipeline issues, and nothing forged or stale. */
class FastJwtVerifierTest {

    private static final String SECRET = "test-secret-0123456789abcdef-0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private final FastJwtVerifier verifier =
            new FastJwtVerifier(SECRET, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsATokenIssuedByTheRealEncoder() {
        assertThat(verifier.subject(token(SECRET, "alice", NOW.plusSeconds(3600)))).contains("alice");
    }

    @Test
    void rejectsAForgedOrTamperedToken() {
        String good = token(SECRET, "alice", NOW.plusSeconds(3600));
        String otherKey = token("another-secret-0123456789abcdef-0123456789", "alice", NOW.plusSeconds(3600));
        String[] parts = good.split("\\.");
        String swappedClaims = parts[0] + "." + payload("{\"sub\":\"mallory\",\"exp\":9999999999}") + "." + parts[2];

        assertThat(verifier.subject(otherKey)).isEmpty();
        assertThat(verifier.subject(swappedClaims)).isEmpty();
        assertThat(verifier.subject(good.substring(0, good.length() - 2))).isEmpty();
    }

    @Test
    void rejectsUnsignedAndWrongAlgorithmTokens() {
        String none = payload("{\"alg\":\"none\"}") + "." + payload("{\"sub\":\"alice\",\"exp\":9999999999}") + ".";
        String hs512 = payload("{\"alg\":\"HS512\"}") + "." + payload("{\"sub\":\"alice\",\"exp\":9999999999}") + ".x";

        assertThat(verifier.subject(none)).isEmpty();
        assertThat(verifier.subject(hs512)).isEmpty();
    }

    @Test
    void honoursExpiryWithTheSameClockSkewAsSpring() {
        assertThat(verifier.subject(token(SECRET, "alice", NOW.minusSeconds(30)))).as("within skew").contains("alice");
        assertThat(verifier.subject(token(SECRET, "alice", NOW.minusSeconds(61)))).as("beyond skew").isEmpty();
    }

    @Test
    void anythingMalformedIsADontKnowNotAnException() {
        for (String junk : new String[] {"", "abc", "a.b", "a.b.c.d", "!!!.###.$$$", ".."}) {
            assertThat(verifier.subject(junk)).isEmpty();
        }
    }

    private static String token(String secret, String sub, Instant exp) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(sub).issuedAt(exp.minusSeconds(7200)).expiresAt(exp).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private static String payload(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
