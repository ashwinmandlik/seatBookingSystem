package io.seatreserve.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings, validated at startup so a misconfigured deploy fails
 * fast instead of misbehaving under load.
 *
 * @param holdTtlSeconds how long a hold lasts before it expires
 * @param jwtSecret      HMAC key for signing and verifying tokens (>= 32 bytes for HS256)
 * @param adminKey       shared secret required to mint an admin token
 * @param hotSeats       in-memory fast-fail for contended seats
 */
@Validated
@ConfigurationProperties("seatreserve")
public record SeatReserveProperties(
        @Min(1) long holdTtlSeconds,
        @NotBlank @Size(min = 32) String jwtSecret,
        @NotBlank String adminKey,
        @Valid @DefaultValue HotSeats hotSeats) {

    /**
     * @param enabled        turn the gate and cache off entirely (correctness does not depend on them)
     * @param cacheTtlMillis how long a "seat is taken" entry is trusted; bounds staleness across instances
     */
    public record HotSeats(@DefaultValue("true") boolean enabled, @DefaultValue("2000") @Min(1) long cacheTtlMillis) {
    }

    public Duration holdTtl() {
        return Duration.ofSeconds(holdTtlSeconds);
    }
}
