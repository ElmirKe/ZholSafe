package kz.zholsafe.config;

import kz.zholsafe.model.Contracts;

/**
 * ZholNet connectivity. No production secrets here — URLs only; credentials come from a future
 * device-identity provider.
 *
 * @param baseUrl                 REST base URL (HTTPS in production)
 * @param webSocketUrl            WebSocket URL for hazard notifications
 * @param offlineQueueCapacity    max hazard events kept while offline (oldest dropped first)
 * @param eventMinIntervalMillis  min interval between submissions for the same hazard class
 * @param nearbyRadiusMeters      radius for nearby-hazard subscription
 */
public record NetworkConfig(
        String baseUrl,
        String webSocketUrl,
        int offlineQueueCapacity,
        long eventMinIntervalMillis,
        int nearbyRadiusMeters) {

    public NetworkConfig {
        java.util.Objects.requireNonNull(baseUrl, "baseUrl");
        java.util.Objects.requireNonNull(webSocketUrl, "webSocketUrl");
        Contracts.positive("offlineQueueCapacity", offlineQueueCapacity);
        Contracts.nonNegative("eventMinIntervalMillis", eventMinIntervalMillis);
        Contracts.positive("nearbyRadiusMeters", nearbyRadiusMeters);
    }

    public static NetworkConfig defaults() {
        return new NetworkConfig(
                "http://10.0.2.2:8080",       // Android emulator → host; override in production
                "ws://10.0.2.2:8080/ws/hazards",
                100,
                10_000L,
                5_000);
    }
}
