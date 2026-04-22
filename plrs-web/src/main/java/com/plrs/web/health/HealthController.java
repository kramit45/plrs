package com.plrs.web.health;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Application-level liveness endpoint. Returns a small JSON payload so the
 * walking skeleton has a testable HTTP surface that is independent of Spring
 * Actuator's {@code /actuator/health} (which may later be gated behind
 * management-port conventions).
 *
 * <p>Traces to: §2.c NFR (observability / liveness), §2.e.2 (operational readiness).
 */
@RestController
public class HealthController {

    private static final String SERVICE = "plrs";
    private static final String VERSION = "0.1.0-SNAPSHOT";

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", SERVICE, VERSION, Instant.now());
    }

    public record HealthResponse(String status, String service, String version, Instant timestamp) {}
}
