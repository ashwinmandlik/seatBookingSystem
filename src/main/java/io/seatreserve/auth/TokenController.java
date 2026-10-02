package io.seatreserve.auth;

import io.seatreserve.common.error.DomainException;
import io.seatreserve.common.error.ErrorCode;
import io.seatreserve.config.SeatReserveProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
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

    static final String USER_ID = "[A-Za-z0-9_.@-]{1,64}";
    static final int MAX_BULK = 25_000;

    public record TokenRequest(@NotBlank @Pattern(regexp = USER_ID) String userId) {
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
        String token = sign(request.userId(), scope, Instant.now());
        return new TokenResponse(token, "Bearer", TOKEN_TTL.toSeconds(), request.userId(), scope);
    }

    /**
     * Either {@code count} users named {@code prefix + 1 .. prefix + count}
     * (prefix defaults to "user-"), or an explicit {@code user_ids} list.
     */
    public record BulkTokenRequest(
            @Min(1) @Max(MAX_BULK) Integer count,
            @Pattern(regexp = "[A-Za-z0-9_.@-]{1,40}") String prefix,
            @Size(min = 1, max = MAX_BULK) List<@NotNull @Pattern(regexp = USER_ID) String> userIds) {

        @AssertTrue(message = "give exactly one of count or user_ids")
        boolean isExactlyOneSource() {
            return (count == null) != (userIds == null);
        }

        List<String> users() {
            if (userIds != null) {
                return userIds;
            }
            String p = prefix == null ? "user-" : prefix;
            return IntStream.rangeClosed(1, count).mapToObj(i -> p + i).toList();
        }
    }

    /** {@code tokens}: user id -> access token, in request order. */
    public record BulkTokenResponse(String tokenType, long expiresIn, int count, Map<String, String> tokens) {
    }

    /**
     * Many user tokens in one call, so a load-testing tool can prepare
     * thousands of buyers without thousands of requests. Admin key required:
     * single tokens are already open, but signing up to 25,000 tokens per call
     * should not be available anonymously.
     */
    @PostMapping("/auth/tokens")
    public BulkTokenResponse issueMany(@Valid @RequestBody BulkTokenRequest request,
                                       @RequestHeader(name = "X-Admin-Key", required = false) String adminKey) {
        if (adminKey == null || !constantTimeEquals(adminKey, props.adminKey())) {
            throw new InvalidAdminKeyException();
        }
        Instant now = Instant.now();
        Map<String, String> tokens = new LinkedHashMap<>();
        for (String user : request.users()) {
            tokens.putIfAbsent(user, sign(user, "user", now));
        }
        return new BulkTokenResponse("Bearer", TOKEN_TTL.toSeconds(), tokens.size(), tokens);
    }

    private String sign(String userId, String scope, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId)
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_TTL))
                .claim("scope", scope)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
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
