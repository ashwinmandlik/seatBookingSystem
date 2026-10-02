package io.seatreserve.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.seatreserve.observability.RequestCorrelationFilter;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Uniform error body:
 * {"error": {"code": ..., "message": ..., "details": {...}}, "request_id": "..."}.
 *
 * <p>{@code request_id} matches the X-Request-Id header and every log line of
 * the request, so a client reporting a failure can be traced in one search.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Body error, String requestId) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Body(ErrorCode code, String message, Map<String, Object> details) {
    }

    public static ApiError of(ErrorCode code, String message, Map<String, Object> details) {
        return new ApiError(new Body(code, message, details), MDC.get(RequestCorrelationFilter.REQUEST_ID));
    }

    public static ApiError of(ErrorCode code, String message) {
        return of(code, message, Map.of());
    }
}
