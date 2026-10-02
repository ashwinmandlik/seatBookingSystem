package io.seatreserve.common.error;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.seatreserve.observability.RequestCorrelationFilter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The single place where outcomes become HTTP. Domain declines are 4xx and are
 * logged at debug only (they are normal traffic during an on-sale burst); only
 * genuinely unexpected failures are logged as errors.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final PropertyNamingStrategies.NamingBase SNAKE_CASE =
            (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE;

    private final MeterRegistry registry;

    public GlobalExceptionHandler(MeterRegistry registry) {
        this.registry = registry;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> domain(DomainException e) {
        log.debug("Declined: {} {}", e.code(), e.getMessage());
        return respond(e.status(), ApiError.of(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        // Report field names as the client sent them (snake_case), not as Java names.
        e.getBindingResult().getFieldErrors()
                .forEach(f -> fields.putIfAbsent(SNAKE_CASE.translate(f.getField()), f.getDefaultMessage()));
        return badRequest(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParameters(HandlerMethodValidationException e) {
        return badRequest(ErrorCode.VALIDATION_FAILED, "Request validation failed", Map.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException e) {
        return badRequest(ErrorCode.VALIDATION_FAILED, e.getMessage(), Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return badRequest(ErrorCode.MALFORMED_REQUEST, "Request body is missing or not valid JSON", Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        // e.g. an id that is not a UUID cannot name any existing resource.
        return respond(HttpStatus.NOT_FOUND, ApiError.of(ErrorCode.NOT_FOUND, "No resource with id '" + e.getValue() + "'"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of(ErrorCode.NOT_FOUND, "Not found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage()));
    }

    /** Database briefly unreachable or saturated: tell the client to retry. */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, TransientDataAccessException.class})
    ResponseEntity<ApiError> unavailable(Exception e) {
        log.warn("Database unavailable: {}", e.getMessage());
        countDatabaseError(e);
        recordOutcome(ErrorCode.SERVICE_UNAVAILABLE);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiError.of(ErrorCode.SERVICE_UNAVAILABLE, "Temporarily unavailable, retry shortly"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        if (e instanceof DataAccessException) {
            countDatabaseError(e);
        }
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of(ErrorCode.INTERNAL_ERROR, "Internal error"));
    }

    /** database_errors_total{exception}: database failures that reached a client. */
    private void countDatabaseError(Exception e) {
        registry.counter("database.errors", "exception", e.getClass().getSimpleName()).increment();
    }

    private static ResponseEntity<ApiError> badRequest(ErrorCode code, String message, Map<String, Object> details) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(code, message, details));
    }

    private static ResponseEntity<ApiError> respond(HttpStatusCode status, ApiError body) {
        recordOutcome(body.error().code());
        return ResponseEntity.status(status).body(body);
    }

    /** Lets the access log line say why the request was turned away. */
    private static void recordOutcome(ErrorCode code) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            request.setAttribute(RequestCorrelationFilter.OUTCOME_ATTRIBUTE, code.name(), RequestAttributes.SCOPE_REQUEST);
        }
    }
}
