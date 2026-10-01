package io.seatreserve.auth;

import io.seatreserve.common.error.DomainException;
import io.seatreserve.common.error.ErrorCode;
import io.seatreserve.config.SeatReserveProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo identity provider so the service can be exercised end to end. In
 * production tokens would come from a real IdP; the rest of the service only
 * depends on a verified JWT whose subject is the user id.
 *
 * <p>Admin tokens additionally require the X-Admin-Key shared secret.
 */
@RestController
public class TokenController {

    static final Duration TOKEN_TTL = Duration.ofHours(12);

    private final JwtEncoder encoder;
    private final SeatReserveProperties props;

    public TokenController(JwtEncoder encoder, SeatReserveProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public record TokenRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.@-]{1,64}") String userId) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, String userId, String scope) {
    }

    @PostMapping("/auth/token")
    public TokenResponse issue(@Valid @RequestBody TokenRequest request,
                               @RequestHeader(name = "X-Admin-Key", required = false) String adminKey) {
        String scope = "user";
        if (adminKey != null) {
            if (!constantTimeEquals(adminKey, props.adminKey())) {
                throw new InvalidAdminKeyException();
            }
            scope = "user " + SecurityConfig.ADMIN_SCOPE;
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(request.userId())
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_TTL))
                .claim("scope", scope)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new TokenResponse(token, "Bearer", TOKEN_TTL.toSeconds(), request.userId(), scope);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static class InvalidAdminKeyException extends DomainException {
        InvalidAdminKeyException() {
            super(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Invalid admin key");
        }
    }
}
