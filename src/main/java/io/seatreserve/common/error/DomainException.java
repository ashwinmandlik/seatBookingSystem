package io.seatreserve.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An expected business outcome that is not success: a seat already taken, a
 * limit reached, an unknown show. These are answers, not failures, so they
 * always map to a 4xx with a stable machine-readable code, never a 5xx.
 *
 * <p>Thrown inside a transaction, it also rolls back every write made so far,
 * which is what makes multi-step operations all-or-nothing.
 */
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;
    private final Map<String, Object> details;

    protected DomainException(HttpStatus status, ErrorCode code, String message, Map<String, Object> details) {
        // Stack traces are pure overhead for expected outcomes on the hot path.
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.details = Map.copyOf(details);
    }

    protected DomainException(HttpStatus status, ErrorCode code, String message) {
        this(status, code, message, Map.of());
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
