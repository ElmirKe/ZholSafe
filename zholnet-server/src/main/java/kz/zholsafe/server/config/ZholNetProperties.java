package kz.zholsafe.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** EXPERIMENTAL / MVP CONFIGURATION. Values are operational heuristics, not safety thresholds. */
@ConfigurationProperties("zholnet.hazards")
public record ZholNetProperties(
        Duration defaultTtl,
        Duration cleanupInterval,
        Duration deduplicationWindow,
        double deduplicationRadiusMeters,
        double maximumNearbyRadiusMeters,
        int maximumNearbyResults,
        String websocketEndpoint,
        String websocketTopic) {

    public ZholNetProperties {
        requirePositive(defaultTtl, "defaultTtl");
        requirePositive(cleanupInterval, "cleanupInterval");
        requirePositive(deduplicationWindow, "deduplicationWindow");
        if (!Double.isFinite(deduplicationRadiusMeters) || deduplicationRadiusMeters <= 0d) {
            throw new IllegalArgumentException("deduplicationRadiusMeters must be finite and positive");
        }
        if (!Double.isFinite(maximumNearbyRadiusMeters) || maximumNearbyRadiusMeters <= 0d) {
            throw new IllegalArgumentException("maximumNearbyRadiusMeters must be finite and positive");
        }
        if (maximumNearbyResults <= 0) {
            throw new IllegalArgumentException("maximumNearbyResults must be positive");
        }
        if (websocketEndpoint == null || websocketEndpoint.isBlank()
                || websocketTopic == null || websocketTopic.isBlank()) {
            throw new IllegalArgumentException("WebSocket endpoint/topic must be configured");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
