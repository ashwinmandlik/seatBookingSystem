package io.seatreserve.reservation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seatreserve.auth.FastJwtVerifier;
import io.seatreserve.common.error.ApiError;
import io.seatreserve.observability.filter.RequestCorrelationFilter;
import io.seatreserve.observability.metrics.ReservationMetrics;
import io.seatreserve.reservation.cache.HotSeatGate;
import io.seatreserve.reservation.service.ReservationDeclines.SeatsUnavailable;
import io.seatreserve.show.api.CreateShowRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers the hot-seat stampede's losers before the expensive part of the stack.
 *
 * <p>In an on-sale burst most requests are losers for a handful of hot seats.
 * Measured on one CPU, every request cost about the same (~3 ms), because a
 * decline still went through Spring Security's JWT pipeline, MVC, JSON
 * binding and validation, even when the hot-seat cache already knew the
 * answer. This filter does only the cheap part: an HMAC check of the token,
 * a read of the small body, and a cache lookup. If <b>every</b> requested seat
 * is known to be taken by someone else, it writes the same {@code 409
 * SEAT_TAKEN} the full path would, without waiting in the write bulkhead.
 *
 * <p>Safe by construction: it can only decline, never grant, and whenever
 * anything is uncertain (token, body, seats, show) it does nothing and the
 * request continues down the normal path unchanged, body included.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)   // after the request-id filter, before the bulkhead
public class FastDeclineFilter extends OncePerRequestFilter {

    private static final Pattern RESERVE_PATH = Pattern.compile("^/shows/([0-9a-fA-F-]{36})/reserve$");
    private static final Pattern SEAT_LABEL = Pattern.compile(CreateShowRequest.SEAT_LABEL);
    private static final int MAX_BODY_BYTES = 4096;
    private static final int MAX_SEATS = 20;

    private final boolean enabled;
    private final FastJwtVerifier tokens;
    private final HotSeatGate hotSeats;
    private final ReservationMetrics metrics;
    private final ObjectMapper json;

    public FastDeclineFilter(@Value("${seatreserve.hot-seats.fast-decline:true}") boolean enabled,
                             FastJwtVerifier tokens, HotSeatGate hotSeats, ReservationMetrics metrics,
                             ObjectMapper json) {
        this.enabled = enabled;
        this.tokens = tokens;
        this.hotSeats = hotSeats;
        this.metrics = metrics;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !"POST".equals(request.getMethod()) || !RESERVE_PATH.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        int length = request.getContentLength();
        if (length < 0 || length > MAX_BODY_BYTES) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(length);
        HttpServletRequest replayable = new CachedBodyRequest(request, body);

        Optional<SeatsUnavailable> decline = knownDecline(request, body);
        if (decline.isEmpty()) {
            chain.doFilter(replayable, response);
            return;
        }
        SeatsUnavailable e = decline.get();
        metrics.request(ReservationMetrics.outcome(e), System.nanoTime() - started);
        metrics.declined(e);
        request.setAttribute(RequestCorrelationFilter.OUTCOME_ATTRIBUTE, e.code().name());
        response.setStatus(e.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ApiError.of(e.code(), e.getMessage(), e.details()));
    }

    /** The decline the full path would also give, or empty if there's any doubt. */
    private Optional<SeatsUnavailable> knownDecline(HttpServletRequest request, byte[] body) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return Optional.empty();
        }
        Optional<String> user = tokens.subject(auth.substring("Bearer ".length()).trim());
        if (user.isEmpty()) {
            return Optional.empty();
        }
        Matcher path = RESERVE_PATH.matcher(request.getRequestURI());
        if (!path.matches()) {
            return Optional.empty();
        }
        List<String> seats = seats(body);
        if (seats == null) {
            return Optional.empty();
        }
        UUID showId;
        try {
            showId = UUID.fromString(path.group(1));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
        MDC.put(RequestCorrelationFilter.USER_ID, user.get());
        try {
            hotSeats.declineIfKnownTaken(showId, seats, user.get());
            return Optional.empty();
        } catch (SeatsUnavailable known) {
            return Optional.of(known);
        }
    }

    /** The requested seats if the body is a well-formed reserve request, otherwise null. */
    private List<String> seats(byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode seats = root == null ? null : root.get("seats");
            if (seats == null || !seats.isArray() || seats.isEmpty() || seats.size() > MAX_SEATS) {
                return null;
            }
            List<String> labels = new ArrayList<>(seats.size());
            for (JsonNode seat : seats) {
                if (!seat.isTextual() || !SEAT_LABEL.matcher(seat.asText()).matches()) {
                    return null;
                }
                labels.add(seat.asText());
            }
            return new HashSet<>(labels).size() == labels.size() ? labels : null;
        } catch (IOException malformed) {
            return null;
        }
    }

    /** Lets the normal path read a body this filter already consumed. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            InputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() throws IOException {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    try {
                        return in.available() == 0;
                    } catch (IOException e) {
                        return true;
                    }
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous body only");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
