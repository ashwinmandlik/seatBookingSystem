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
        @Valid @DefaultValue HotSeats hotSeats,
        @Valid @DefaultValue IdleAware idleAware) {

    /**
     * @param enabled        let background jobs leave an idle database alone (for pause-when-idle databases)
     * @param maxIdleMinutes the longest a sweep or gauge refresh is skipped while idle
     */
    public record IdleAware(@DefaultValue("false") boolean enabled, @DefaultValue("60") @Min(1) long maxIdleMinutes) {
    }

    /**
     * @param enabled        turn the gate and caches off entirely (correctness does not depend on them)
     * @param cacheTtlMillis how long a local "seat is taken" entry is trusted
     * @param sharedCache    optional Redis layer shared by all instances
     * @param maxEntries     safety cap on locally cached seats (~200 bytes each); cleared when reached
     */
    public record HotSeats(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2000") @Min(1) long cacheTtlMillis,
            @Valid @DefaultValue SharedCache sharedCache,
            @DefaultValue("500000") @Min(1) int maxEntries) {
    }

    /**
     * @param enabled       use Redis as a shared L2 cache
     * @param ttlMillis     how long a shared entry lives; the worst-case staleness bound
     * @param breakerMillis how long to bypass Redis after an error
     */
    public record SharedCache(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("5000") @Min(1) long ttlMillis,
            @DefaultValue("5000") @Min(1) long breakerMillis) {
    }

    public Duration holdTtl() {
        return Duration.ofSeconds(holdTtlSeconds);
    }
}
