package io.seatreserve.observability.filter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bulkhead for write requests (reserve, confirm, cancel, create show, tokens):
 * at most {@code maxConcurrent} are processed at once; the rest wait their turn
 * in a fair FIFO queue.
 *
 * <p>Why: every request runs on its own virtual thread, so an on-sale burst
 * makes thousands of threads runnable at once. On a small CPU they all
 * time-slice, each finishes late, memory grows with every in-flight request,
 * and a health check queues behind all of them; the platform then mistakes
 * "busy" for "dead" and restarts the instance. Behind the bulkhead, waiting
 * requests are parked before authentication or body parsing (almost no CPU or
 * memory), the CPU serves a handful of requests at a time so each finishes
 * quickly, and reads, health checks and metrics bypass it entirely.
 *
 * <p>It never rejects: a 503 would be a server error and a 429 is not an answer
 * the API promises, so requests wait instead.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)   // after the request-id and fast-decline filters
public class WriteBulkheadFilter extends OncePerRequestFilter {

    private final Semaphore permits;
    private final AtomicInteger waiting = new AtomicInteger();
    private final Timer queueTime;

    public WriteBulkheadFilter(@Value("${seatreserve.bulkhead.max-concurrent-writes:32}") int maxConcurrent,
                               MeterRegistry registry) {
        this.permits = new Semaphore(maxConcurrent, true);
        this.queueTime = Timer.builder("bulkhead.queue.time")
                .description("Time write requests waited for a bulkhead permit")
                .publishPercentileHistogram()
                .register(registry);
        Gauge.builder("bulkhead.queue.waiting", waiting, AtomicInteger::get)
                .description("Write requests waiting for a bulkhead permit").register(registry);
        Gauge.builder("bulkhead.in.flight", permits, p -> maxConcurrent - p.availablePermits())
                .description("Write requests being processed").register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Reads, health checks and metrics are cheap and must stay responsive.
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long queued = System.nanoTime();
        waiting.incrementAndGet();
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServletException("Interrupted while waiting for a bulkhead permit", e);
        } finally {
            waiting.decrementAndGet();
        }
        queueTime.record(System.nanoTime() - queued, java.util.concurrent.TimeUnit.NANOSECONDS);
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}
