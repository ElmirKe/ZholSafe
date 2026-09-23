package kz.zholsafe.network;

import kz.zholsafe.model.Contracts;

import java.time.Instant;
import java.util.Objects;

/** Stage 5 compact request DTO. Contains metadata only: no media, identity or biometrics. */
public record NetworkHazardEvent(String eventId, int schemaVersion, String vehicleId,
        NetworkHazardType hazardType, NetworkSeverity severity, float confidence, float risk,
        double latitude, double longitude, Instant timestamp, String status,
        Float headingDegrees, Float approximateDistanceMeters, Float ttcSeconds) {
    public NetworkHazardEvent {
        if (eventId == null || eventId.isBlank() || vehicleId == null || vehicleId.isBlank()) {
            throw new IllegalArgumentException("event/source identifiers are required");
        }
        if (schemaVersion != 1) throw new IllegalArgumentException("schemaVersion must be 1");
        Objects.requireNonNull(hazardType, "hazardType");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(timestamp, "timestamp");
        if (!"ACTIVE".equals(status)) throw new IllegalArgumentException("client status must be ACTIVE");
        Contracts.unit("confidence", confidence);
        Contracts.unit("risk (engineering severity, not probability)", risk);
        Contracts.range("latitude", latitude, -90d, 90d);
        Contracts.range("longitude", longitude, -180d, 180d);
        optionalRange("headingDegrees", headingDegrees, 0f, 360f, false);
        optionalRange("approximateDistanceMeters", approximateDistanceMeters, 0f, Float.MAX_VALUE, true);
        optionalRange("ttcSeconds", ttcSeconds, 0f, Float.MAX_VALUE, false);
    }

    private static void optionalRange(String name, Float value, float min, float max, boolean strict) {
        if (value != null && (!Float.isFinite(value) || value < min || value >= max
                || (strict && value == 0f))) throw new IllegalArgumentException("invalid " + name);
    }
}
