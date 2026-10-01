package io.seatreserve.reservation;

import io.seatreserve.common.error.DomainException;
import io.seatreserve.common.error.ErrorCode;
import io.seatreserve.reservation.ReservationService.ReserveResult;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    /**
     * 201 with the new reservation, or 200 with the original one when the
     * idempotency key was already used for this exact request. A replay is not
     * a second sale, so it deliberately does not return 201 again.
     */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID showId,
            @Valid @RequestBody ReserveRequest request,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String headerKey,
            @AuthenticationPrincipal Jwt principal) {
        ReserveCommand cmd = new ReserveCommand(showId, principal.getSubject(), request.seats(),
                Boolean.TRUE.equals(request.hold()), idempotencyKey(headerKey, request.idempotencyKey()));
        ReserveResult result = service.reserve(cmd);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(ReservationResponse.of(result.reservation()));
    }

    /** Owner only; anyone else gets 404, so reservation ids cannot be probed. */
    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse get(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt principal) {
        return ReservationResponse.of(service.get(reservationId, principal.getSubject()));
    }

    /** Turns a hold into a sale. Idempotent: confirming twice returns the same reservation. */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ReservationResponse confirm(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt principal) {
        return ReservationResponse.of(service.confirm(reservationId, principal.getSubject()));
    }

    /** Owner only. Idempotent: cancelling twice returns the cancelled reservation. */
    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt principal) {
        return ReservationResponse.of(service.cancel(reservationId, principal.getSubject()));
    }

    /** The key may arrive in the header or the body; if both, they must agree. */
    private static String idempotencyKey(String header, String body) {
        if (header != null && body != null && !header.equals(body)) {
            throw new InvalidIdempotencyKey();
        }
        String key = header != null ? header : body;
        if (key != null && (key.isBlank() || key.length() > 128)) {
            throw new InvalidIdempotencyKey();
        }
        return key;
    }

    static class InvalidIdempotencyKey extends DomainException {
        InvalidIdempotencyKey() {
            super(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "Idempotency key must be 1-128 characters, and header and body keys must match");
        }
    }
}
