package kz.zholsafe.server.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** Liveness endpoint. Replaced/augmented by Spring Actuator in Stage 5. */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "zholnet-server",
                "contractVersion", 1,
                "timestamp", Instant.now().toString());
    }
}
