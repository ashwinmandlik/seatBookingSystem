package io.seatreserve.reservation.cache;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;

/**
 * Redis-backed {@link SharedSeatCache}.
 *
 * <p>Keys: {@code seat:taken:{showId}:label -> ownerId}, with a short TTL.
 * The {@code {showId}} hash tag keeps all of a show's seats in one Redis
 * Cluster slot, so a multi-seat MGET or DEL is a single command even when
 * Redis is sharded.
 *
 * <p>The TTL is the real staleness bound, not just a safety net: an instance
 * can learn "A12 -> alice" from a decline, alice cancels elsewhere and DELs,
 * and the late SET lands after the DEL. The result is a wrong "taken" until
 * the TTL lapses, never a wrong sale, so the TTL is kept short.
 *
 * <p>Fail open with a circuit breaker: any Redis error makes this cache act
 * empty for {@code breakerMillis}, so a dead or slow Redis costs at most one
 * timeout per window instead of one per request.
 */
class RedisSharedSeatCache implements SharedSeatCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSharedSeatCache.class);

    private final StringRedisTemplate redis;
    private final long ttlMillis;
    private final long breakerNanos;
    private final LongSupplier nanoClock;
    private volatile long openUntilNanos;
    private volatile boolean open;

    private final Counter errors;

    RedisSharedSeatCache(StringRedisTemplate redis, Duration ttl, Duration breaker, LongSupplier nanoClock,
                         MeterRegistry registry) {
        this.redis = redis;
        this.errors = Counter.builder("shared.cache.errors").description("Redis calls that failed")
                .register(registry);
        Gauge.builder("shared.cache.breaker.open", this, c -> c.open ? 1 : 0)
                .description("1 while Redis is being bypassed after an error")
                .register(registry);
        this.ttlMillis = ttl.toMillis();
        this.breakerNanos = breaker.toNanos();
        this.nanoClock = nanoClock;
    }

    static String key(UUID showId, String label) {
        return "seat:taken:{" + showId + "}:" + label;
    }

    @Override
    public Map<String, String> owners(UUID showId, List<String> labels) {
        if (skipping()) {
            return Map.of();
        }
        try {
            List<String> values = redis.opsForValue().multiGet(labels.stream().map(l -> key(showId, l)).toList());
            if (values == null) {
                return Map.of();
            }
            Map<String, String> owners = new HashMap<>();
            for (int i = 0; i < labels.size(); i++) {
                if (values.get(i) != null) {
                    owners.put(labels.get(i), values.get(i));
                }
            }
            return owners;
        } catch (RuntimeException e) {
            trip(e);
            return Map.of();
        }
    }

    @Override
    public void markTaken(UUID showId, Map<String, String> ownerByLabel) {
        if (ownerByLabel.isEmpty() || skipping()) {
            return;
        }
        try {
            Expiration ttl = Expiration.milliseconds(ttlMillis);
            redis.executePipelined((RedisCallback<Object>) connection -> {
                ownerByLabel.forEach((label, owner) -> connection.stringCommands().set(
                        key(showId, label).getBytes(UTF_8), owner.getBytes(UTF_8), ttl, SetOption.upsert()));
                return null;
            });
        } catch (RuntimeException e) {
            trip(e);
        }
    }

    @Override
    public void forget(UUID showId, List<String> labels) {
        if (labels.isEmpty() || skipping()) {
            return;
        }
        try {
            redis.delete(labels.stream().map(l -> key(showId, l)).toList());
        } catch (RuntimeException e) {
            trip(e);
        }
    }

    private boolean skipping() {
        if (!open) {
            return false;
        }
        if (nanoClock.getAsLong() - openUntilNanos >= 0) {
            open = false;
            log.info("Shared seat cache: retrying Redis");
            return false;
        }
        return true;
    }

    private void trip(RuntimeException e) {
        errors.increment();
        if (!open) {
            log.warn("Shared seat cache unavailable, bypassing Redis for {} ms: {}",
                    breakerNanos / 1_000_000, e.getMessage());
        }
        openUntilNanos = nanoClock.getAsLong() + breakerNanos;
        open = true;
    }
}
