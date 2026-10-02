package io.seatreserve.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seatreserve.config.SeatReserveProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Minimal HS256 verification for the hot-path decline filter: an HMAC and two
 * small JSON reads instead of the full Spring Security + Nimbus pipeline.
 *
 * <p>It accepts exactly what the main pipeline accepts (HS256, valid
 * signature, {@code exp}/{@code nbf} with the same 60 s clock skew as
 * Spring's JwtTimestampValidator) and answers "don't know" (empty) for
 * anything else, so the caller falls through to the full pipeline, which then
 * produces the authoritative 401. It never accepts a token the main pipeline
 * would reject.
 */
@Component
public class FastJwtVerifier {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final Mac prototype;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public FastJwtVerifier(SeatReserveProperties props, ObjectMapper json) {
        this(props.jwtSecret(), json, Clock.systemUTC());
    }

    FastJwtVerifier(String secret, ObjectMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
        try {
            this.prototype = Mac.getInstance("HmacSHA256");
            prototype.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** The verified subject (user id), or empty if the token is not certainly valid. */
    public Optional<String> subject(String token) {
        try {
            int first = token.indexOf('.');
            int second = token.indexOf('.', first + 1);
            if (first <= 0 || second <= first + 1 || second == token.length() - 1
                    || token.indexOf('.', second + 1) >= 0) {
                return Optional.empty();
            }
            Base64.Decoder b64 = Base64.getUrlDecoder();
            JsonNode header = json.readTree(b64.decode(token.substring(0, first)));
            if (!"HS256".equals(header.path("alg").asText()) || header.has("crit")) {
                return Optional.empty();
            }
            byte[] expected = mac().doFinal(token.substring(0, second).getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, b64.decode(token.substring(second + 1)))) {
                return Optional.empty();
            }
            JsonNode claims = json.readTree(b64.decode(token.substring(first + 1, second)));
            long now = clock.instant().getEpochSecond();
            if (!claims.path("exp").isNumber() || now > claims.get("exp").asLong() + CLOCK_SKEW.toSeconds()) {
                return Optional.empty();
            }
            if (claims.has("nbf") && now < claims.path("nbf").asLong() - CLOCK_SKEW.toSeconds()) {
                return Optional.empty();
            }
            String sub = claims.path("sub").asText(null);
            return sub == null || sub.isBlank() ? Optional.empty() : Optional.of(sub);
        } catch (Exception malformed) {
            return Optional.empty();
        }
    }

    private Mac mac() throws CloneNotSupportedException {
        return (Mac) prototype.clone();   // cheap copy of an initialised Mac; Mac is not thread-safe
    }
}
