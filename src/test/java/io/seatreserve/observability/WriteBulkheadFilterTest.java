package io.seatreserve.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WriteBulkheadFilterTest {

    private final WriteBulkheadFilter bulkhead = new WriteBulkheadFilter(3, new SimpleMeterRegistry());

    @Test
    void neverProcessesMoreWritesAtOnceThanItsPermits() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        FilterChain slowWork = (req, res) -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inside.decrementAndGet();
        };

        runConcurrently(50, () -> bulkhead.doFilter(post(), new MockHttpServletResponse(), slowWork));

        assertThat(maxInside).hasValue(3);
    }

    @Test
    void readsAndHealthChecksBypassAFullBulkhead() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FilterChain blocked = (req, res) -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < 3; i++) {   // occupy every permit
            pool.submit(() -> {
                bulkhead.doFilter(post(), new MockHttpServletResponse(), blocked);
                return null;
            });
        }
        Thread.sleep(100);

        AtomicInteger served = new AtomicInteger();
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/health/live");
        long started = System.nanoTime();
        bulkhead.doFilter(health, new MockHttpServletResponse(), (req, res) -> served.incrementAndGet());

        assertThat(served).hasValue(1);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(100);
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private static MockHttpServletRequest post() {
        return new MockHttpServletRequest("POST", "/shows/x/reserve");
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void runConcurrently(int n, Body body) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    body.run();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
    }
}
