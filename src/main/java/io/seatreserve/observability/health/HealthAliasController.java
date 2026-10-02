package io.seatreserve.observability.health;

import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the liveness and readiness probes at the conventional
 * {@code /health/live} and {@code /health/ready} as well as {@code /livez} and
 * {@code /readyz}. Spring Boot only maps single-segment extra paths itself, so
 * this delegates to the same health groups with the same status mapping:
 * 200 when UP, 503 when DOWN.
 */
@RestController
public class HealthAliasController {

    private final HealthEndpoint health;
    private final HttpCodeStatusMapper statusCodes;

    public HealthAliasController(HealthEndpoint health, HttpCodeStatusMapper statusCodes) {
        this.health = health;
        this.statusCodes = statusCodes;
    }

    @GetMapping("/health/live")
    public ResponseEntity<HealthComponent> live() {
        return group("liveness");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<HealthComponent> ready() {
        return group("readiness");
    }

    private ResponseEntity<HealthComponent> group(String name) {
        HealthComponent result = health.healthForPath(name);
        return ResponseEntity.status(statusCodes.getStatusCode(result.getStatus())).body(result);
    }
}
