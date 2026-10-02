package io.seatreserve.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import io.seatreserve.IntegrationTest;
import io.seatreserve.Invariants;
import io.seatreserve.reservation.ReservationDeclines.SeatsUnavailable;
import io.seatreserve.show.CreateShowRequest;
import io.seatreserve.show.ShowService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.embedded.RedisServer;

/** The reserve path with the Redis L2 enabled: once with Redis healthy, once with it unreachable. */
class SharedCacheIntegrationTest {

    @Nested
    class WithRedisUp extends Base {

        static final int PORT = freePort();
        static final RedisServer REDIS = startRedis(PORT);

        @DynamicPropertySource
        static void redis(DynamicPropertyRegistry registry) {
            registry.add("seatreserve.hot-seats.shared-cache.enabled", () -> "true");
            registry.add("spring.data.redis.url", () -> "redis://localhost:" + PORT);
        }

        @Autowired
        StringRedisTemplate redis;

        @Test
        void soldSeatsArePublishedToRedisAndCancelRemovesThem() throws Exception {
            UUID show = createShow(10);
            Outcomes outcomes = race(200, i -> new ReserveCommand(show, "user-" + i, List.of("A1"), false, "k" + i));

            assertThat(outcomes.successes).hasSize(1);
            assertThat(outcomes.seatTaken).isEqualTo(199);
            assertThat(outcomes.unexpected).isEmpty();
            String key = RedisSharedSeatCache.key(show, "A1");
            Reservation winner = outcomes.successes.getFirst();
            assertThat(redis.opsForValue().get(key)).isEqualTo(winner.userId());

            service.cancel(winner.id(), winner.userId());
            assertThat(redis.hasKey(key)).isFalse();
            Invariants.assertConsistent(jdbc, show);
        }
    }

    @Nested
    class WithRedisDown extends Base {

        @DynamicPropertySource
        static void deadRedis(DynamicPropertyRegistry registry) {
            registry.add("seatreserve.hot-seats.shared-cache.enabled", () -> "true");
            // Nothing listens here: every Redis call fails.
            registry.add("spring.data.redis.url", () -> "redis://localhost:" + freePort());
        }

        @Test
        void aDeadRedisChangesNothingAboutCorrectness() throws Exception {
            UUID show = createShow(10);
            Outcomes outcomes = race(300, i -> new ReserveCommand(show, "user-" + i, List.of("A1"), false, "k" + i));

            assertThat(outcomes.successes).hasSize(1);
            assertThat(outcomes.seatTaken).isEqualTo(299);
            assertThat(outcomes.unexpected).isEmpty();
            Invariants.assertConsistent(jdbc, show);
        }
    }

    abstract static class Base extends IntegrationTest {

        @Autowired
        ReservationService service;

        @Autowired
        ShowService shows;

        @Autowired
        JdbcClient jdbc;

        UUID createShow(int seats) {
            List<String> labels = IntStream.rangeClosed(1, seats).mapToObj(i -> "A" + i).toList();
            return shows.create(new CreateShowRequest("redis", labels, 25_000L, 4)).id();
        }

        record Outcomes(List<Reservation> successes, int seatTaken, List<Throwable> unexpected) {
        }

        interface Commands {
            ReserveCommand of(int index);
        }

        Outcomes race(int n, Commands commands) throws Exception {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ReservationService.ReserveResult>> futures = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < n; i++) {
                    ReserveCommand cmd = commands.of(i);
                    futures.add(pool.submit(() -> {
                        go.await();
                        return service.reserve(cmd);
                    }));
                }
                go.countDown();
                List<Reservation> successes = new ArrayList<>();
                List<Throwable> unexpected = new ArrayList<>();
                int seatTaken = 0;
                for (var f : futures) {
                    try {
                        successes.add(f.get().reservation());
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof SeatsUnavailable) {
                            seatTaken++;
                        } else {
                            unexpected.add(e.getCause());
                        }
                    }
                }
                return new Outcomes(successes, seatTaken, unexpected);
            }
        }
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static RedisServer startRedis(int port) {
        try {
            RedisServer server = new RedisServer(port);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
