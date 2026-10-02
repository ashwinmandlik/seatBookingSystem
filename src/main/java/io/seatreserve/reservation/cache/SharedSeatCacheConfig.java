package io.seatreserve.reservation.cache;

import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.lettuce.core.ClientOptions;
import io.micrometer.core.instrument.MeterRegistry;
import io.seatreserve.config.SeatReserveProperties.SharedCache;
import io.seatreserve.config.SeatReserveProperties;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
class SharedSeatCacheConfig {

    /** Redis when enabled, otherwise no shared layer: the service needs neither. */
    @Bean
    SharedSeatCache sharedSeatCache(SeatReserveProperties props, ObjectProvider<StringRedisTemplate> redis,
                                    MeterRegistry registry) {
        SharedCache config = props.hotSeats().sharedCache();
        if (!props.hotSeats().enabled() || !config.enabled()) {
            return SharedSeatCache.NONE;
        }
        return new RedisSharedSeatCache(redis.getObject(), Duration.ofMillis(config.ttlMillis()),
                Duration.ofMillis(config.breakerMillis()), System::nanoTime, registry);
    }

    /**
     * While Redis is unreachable, fail commands immediately instead of
     * queueing them until reconnect: a request should never wait on an
     * optional cache.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastWhenDisconnected() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .build());
    }
}
