package io.seatreserve.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.seatreserve.common.error.ApiError;
import io.seatreserve.common.error.ErrorCode;
import io.seatreserve.config.SeatReserveProperties;
import io.seatreserve.observability.AuthenticatedUserMdcFilter;
import io.seatreserve.observability.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless bearer-token auth. The authenticated user id is the JWT subject;
 * nothing in a request body can influence who the caller is.
 */
@Configuration
public class SecurityConfig {

    public static final String ADMIN_SCOPE = "admin";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json) throws Exception {
        AuthenticationEntryPoint unauthorized = (req, res, e) -> write(req, res, json,
                HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Missing or invalid bearer token");
        AccessDeniedHandler forbidden = (req, res, e) -> write(req, res, json,
                HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN, "Not allowed");
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        .requestMatchers("/livez", "/readyz", "/health/live", "/health/ready", "/actuator/health/**", "/actuator/prometheus",
                                "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/shows/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/shows").hasAuthority("SCOPE_" + ADMIN_SCOPE)
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> {})
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                // Every log line after authentication carries the token's user id.
                .addFilterAfter(new AuthenticatedUserMdcFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(SeatReserveProperties props) {
        return NimbusJwtDecoder.withSecretKey(key(props)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder(SeatReserveProperties props) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(props)));
    }

    private static SecretKey key(SeatReserveProperties props) {
        return new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static void write(HttpServletRequest req, HttpServletResponse res, ObjectMapper json, int status,
                              ErrorCode code, String message) throws IOException {
        req.setAttribute(RequestCorrelationFilter.OUTCOME_ATTRIBUTE, code.name());
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getOutputStream(), ApiError.of(code, message));
    }
}
