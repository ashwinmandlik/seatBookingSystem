package io.seatreserve.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Outermost filter: gives every request a correlation id and writes one
 * structured access-log line when it completes.
 *
 * <p>The id is taken from an inbound {@code X-Request-Id} (so a caller or a
 * proxy can correlate across systems) if it looks safe, otherwise generated.
 * It is put in the MDC, so every log line written while handling the request
 * carries {@code request_id}, and echoed in the response header, so a client
 * reporting a problem can quote it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String REQUEST_ID = "request_id";
    public static final String USER_ID = "user_id";
    /** Set by the exception handler so the access log can say why a request was declined. */
    public static final String OUTCOME_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".outcome";

    private static final Logger access = LoggerFactory.getLogger("access");
    /** Accept only ids that cannot inject anything into logs or headers. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final DeclineLogSampler sampler;

    public RequestCorrelationFilter(DeclineLogSampler sampler) {
        this.sampler = sampler;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String requestId = inbound != null && SAFE_ID.matcher(inbound).matches()
                ? inbound
                : UUID.randomUUID().toString();
        MDC.put(REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            Object outcome = request.getAttribute(OUTCOME_ATTRIBUTE);
            int sampleRate = isProbe(request) ? 0 : sampler.decide(outcome);
            if (sampleRate > 0) {
                var line = access.atInfo()
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("path", request.getRequestURI())
                        .addKeyValue("status", response.getStatus())
                        .addKeyValue("outcome", outcome != null ? outcome : "ok")
                        .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000);
                if (sampleRate > 1) {
                    line = line.addKeyValue("sample_rate", sampleRate);   // this line stands for N declines
                }
                line.log("{} {} -> {}", request.getMethod(), request.getRequestURI(), response.getStatus());
            }
            MDC.clear();
        }
    }

    /** Health checks and metric scrapes every few seconds would drown the useful lines. */
    private static boolean isProbe(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/health/")
                || path.equals("/livez") || path.equals("/readyz");
    }
}
