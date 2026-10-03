package io.seatreserve.reservation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.lettuce.core.ClientOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.seatreserve.reservation.service.ReservationDeclines.SeatsUnavailable;
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

/**
 * Runs against a real redis-server process.
 *
 * <p>Timings are generous on purpose: a slow CI machine (GitHub's Windows runners) once took longer than a
 * 200 ms command timeout or a 300 ms entry TTL between a write and the read that checks it, and the cache,
 * which never throws, quietly skipped the write. Only {@link #entriesExpireOnTheirOwn} uses a short TTL.
 */
class RedisSharedSeatCacheTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration SHORT_TTL = Duration.ofMillis(300);
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
                        .commandTimeout(Duration.ofSeconds(2))
                        .clientOptions(ClientOptions.builder()
                                .disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS).build())
                        .build());
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        // Connect now, so a slow first connection never counts against a test's first command.
        try (var connection = factory.getConnection()) {
            connection.ping();
        }
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
        RedisSharedSeatCache shortLived = new RedisSharedSeatCache(redis, SHORT_TTL, BREAKER, now::get,
                new SimpleMeterRegistry());
        shortLived.markTaken(show, Map.of("A1", "alice"));
        Thread.sleep(SHORT_TTL.toMillis() + 200);

        assertThat(shortLived.owners(show, List.of("A1"))).isEmpty();
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
        awaitReconnected();                                // Redis is reachable again...
        cache.markTaken(show, Map.of("A1", "alice"));      // ...but skipped: breaker still open
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

    /** After a restart, the client reconnects in the background and rejects commands until it has. */
    private void awaitReconnected() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            try (var connection = factory.getConnection()) {
                connection.ping();
                return;
            } catch (RuntimeException notYet) {
                if (System.nanoTime() > deadline) {
                    throw notYet;
                }
                Thread.sleep(50);
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
