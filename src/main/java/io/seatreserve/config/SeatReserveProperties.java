package io.seatreserve.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings, validated at startup so a misconfigured deploy fails
 * fast instead of misbehaving under load.
 *
 * @param holdTtlSeconds how long a hold lasts before it expires
 * @param jwtSecret      HMAC key for signing and verifying tokens (>= 32 bytes for HS256)
 * @param adminKey       shared secret required to mint an admin token
 */
@Validated
@ConfigurationProperties("seatreserve")
public record SeatReserveProperties(
        @Min(1) long holdTtlSeconds,
        @NotBlank @Size(min = 32) String jwtSecret,
        @NotBlank String adminKey) {

    public Duration holdTtl() {
        return Duration.ofSeconds(holdTtlSeconds);
    }
}
