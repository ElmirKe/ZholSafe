package kz.zholsafe.location;

import kz.zholsafe.model.Contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalDouble;

/** Immutable location fix with both wall-clock and monotonic timestamps. */
public record LocationFix(double latitude, double longitude, Instant timestamp,
        long elapsedRealtimeNanos, double accuracyMeters, OptionalDouble bearingDegrees,
        OptionalDouble speedMetersPerSecond, LocationQuality quality) {
    public LocationFix {
        Contracts.range("latitude", latitude, -90d, 90d);
        Contracts.range("longitude", longitude, -180d, 180d);
        Objects.requireNonNull(timestamp, "timestamp");
        if (elapsedRealtimeNanos < 0 || !Double.isFinite(accuracyMeters) || accuracyMeters < 0) {
            throw new IllegalArgumentException("invalid location time/accuracy");
        }
        bearingDegrees = Objects.requireNonNull(bearingDegrees, "bearingDegrees");
        speedMetersPerSecond = Objects.requireNonNull(speedMetersPerSecond, "speedMetersPerSecond");
        Objects.requireNonNull(quality, "quality");
        if (bearingDegrees.isPresent() && (!Double.isFinite(bearingDegrees.getAsDouble())
                || bearingDegrees.getAsDouble() < 0 || bearingDegrees.getAsDouble() >= 360)) {
            throw new IllegalArgumentException("bearing must be finite and in [0,360)");
        }
        if (speedMetersPerSecond.isPresent() && (!Double.isFinite(speedMetersPerSecond.getAsDouble())
                || speedMetersPerSecond.getAsDouble() < 0)) {
            throw new IllegalArgumentException("speed must be finite and non-negative");
        }
    }

    public boolean freshAt(long observationElapsedNanos, long maximumAgeNanos) {
        if (observationElapsedNanos < 0 || maximumAgeNanos < 0) return false;
        long age = observationElapsedNanos - elapsedRealtimeNanos;
        return age >= 0 && age <= maximumAgeNanos;
    }
}
