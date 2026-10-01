package io.seatreserve.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** Uniform error body: {"error": {"code": ..., "message": ..., "details": {...}}}. */
public record ApiError(Body error) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Body(ErrorCode code, String message, Map<String, Object> details) {
    }

    public static ApiError of(ErrorCode code, String message, Map<String, Object> details) {
        return new ApiError(new Body(code, message, details));
    }

    public static ApiError of(ErrorCode code, String message) {
        return of(code, message, Map.of());
    }
}
