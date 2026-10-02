package io.seatreserve.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.seatreserve.reservation.ReservationDeclines.SeatsUnavailable;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

/** Runs against a real redis-server process. */
class RedisSharedSeatCacheTest {

    private static final Duration TTL = Duration.ofMillis(300);
    private static final Duration BREAKER = Duration.ofSeconds(5);

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final UUID show = UUID.randomUUID();
    private int port;
    private RedisServer server;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisSharedSeatCache cache;

    @BeforeEach
    void start() throws IOException {
        port = freePort();
        server = new RedisServer(port);
        server.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", port),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(200))
                        .clientOptions(ClientOptions.builder()
                                .disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS).build())
                        .build());
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        cache = new RedisSharedSeatCache(redis, TTL, BREAKER, now::get, new SimpleMeterRegistry());
    }

    @AfterEach
    void stop() throws IOException {
        factory.destroy();
        if (server.isActive()) {
            server.stop();
        }
    }

    @Test
    void marksReadsAndForgetsSeats() {
        cache.markTaken(show, Map.of("A1", "alice", "A2", "alice"));

        assertThat(cache.owners(show, List.of("A1", "A2", "A3"))).containsExactlyInAnyOrderEntriesOf(
                Map.of("A1", "alice", "A2", "alice"));

        cache.forget(show, List.of("A1"));
        assertThat(cache.owners(show, List.of("A1", "A2"))).containsOnlyKeys("A2");
    }

    @Test
    void usesAShowHashTagSoAShowsSeatsShareOneClusterSlot() {
        cache.markTaken(show, Map.of("A12", "alice"));

        assertThat(redis.keys("seat:taken:*")).containsExactly("seat:taken:{" + show + "}:A12");
    }

    @Test
    void entriesExpireOnTheirOwn() throws InterruptedException {
        cache.markTaken(show, Map.of("A1", "alice"));
        Thread.sleep(TTL.toMillis() + 200);

        assertThat(cache.owners(show, List.of("A1"))).isEmpty();
    }

    @Test
    void twoInstancesShareWhatTheyLearnAndACancelClearsItForBoth() {
        AtomicLong vmClock = new AtomicLong(0);
        long localTtl = Duration.ofSeconds(2).toNanos();
        HotSeatGate vm1 = new HotSeatGate(true, localTtl, vmClock::get, cache);
        HotSeatGate vm2 = new HotSeatGate(true, localTtl, vmClock::get,
                new RedisSharedSeatCache(redis, TTL, BREAKER, now::get, new SimpleMeterRegistry()));

        // alice buys A1 on VM 1; VM 2 has never seen A1 but declines bob from Redis.
        vm1.markTaken(show, Map.of("A1", "alice"));
        assertThatThrownBy(() -> vm2.declineIfKnownTaken(show, List.of("A1"), "bob"))
                .isInstanceOfSatisfying(SeatsUnavailable.class, e -> assertThat(e.fastPath()).isTrue());
        // alice herself is let through to the database (idempotent replay).
        assertThatCode(() -> vm2.declineIfKnownTaken(show, List.of("A1"), "alice")).doesNotThrowAnyException();

        // alice cancels on VM 1: the shared entry is gone at once. VM 2's own copy
        // (learned above) lives until its short local TTL.
        vm1.forget(show, List.of("A1"));
        assertThat(cache.owners(show, List.of("A1"))).isEmpty();
        vmClock.addAndGet(localTtl + 1);
        assertThatCode(() -> vm2.declineIfKnownTaken(show, List.of("A1"), "bob")).doesNotThrowAnyException();
    }

    @Test
    void redisDownMeansAnEmptyCacheNotAnError() throws IOException {
        cache.markTaken(show, Map.of("A1", "alice"));
        server.stop();

        assertThatCode(() -> {
            assertThat(cache.owners(show, List.of("A1"))).isEmpty();
            cache.markTaken(show, Map.of("A2", "bob"));
            cache.forget(show, List.of("A1"));
        }).doesNotThrowAnyException();
    }

    @Test
    void breakerSkipsRedisWhileOpenAndRecoversAfterwards() throws Exception {
        server.stop();
        cache.owners(show, List.of("A1"));                  // trips the breaker

        server = new RedisServer(port);
        server.start();
        cache.markTaken(show, Map.of("A1", "alice"));      // skipped: breaker still open
        assertThat(redis.keys("seat:taken:*")).isEmpty();

        // Once the breaker window has passed (and Lettuce has reconnected), Redis is used again.
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (cache.owners(show, List.of("A1")).isEmpty() && System.nanoTime() < deadline) {
            now.addAndGet(BREAKER.toNanos() + 1);
            cache.markTaken(show, Map.of("A1", "alice"));
            Thread.sleep(100);
        }
        assertThat(cache.owners(show, List.of("A1"))).containsEntry("A1", "alice");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
