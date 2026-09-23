package kz.zholsafe.model;

/**
 * GNSS fix. {@code accuracyMeters}, {@code speedMps} and {@code bearingDeg} are optional and use
 * {@code Float.NaN} as the documented "not provided" sentinel: check {@link #accuracyAvailable()},
 * {@link #speedAvailable()} and {@link #bearingAvailable()} before use. When provided they must be
 * finite (accuracy and speed non-negative). Latitude/longitude are required and must be finite
 * and in range.
 *
 * @param latitude        WGS84 degrees
 * @param longitude       WGS84 degrees
 * @param accuracyMeters  horizontal accuracy radius as reported by the provider (NaN if unknown)
 * @param speedMps        ground speed in m/s (NaN if not provided)
 * @param bearingDeg      heading in degrees (NaN if not provided)
 * @param timestampMillis wall-clock epoch millis of the fix
 */
public record GeoPosition(
        double latitude,
        double longitude,
        float accuracyMeters,
        float speedMps,
        float bearingDeg,
        long timestampMillis) {

    public GeoPosition {
        Contracts.range("latitude", latitude, -90d, 90d);
        Contracts.range("longitude", longitude, -180d, 180d);
        Contracts.finiteOrNaN("accuracyMeters", accuracyMeters);
        Contracts.finiteOrNaN("speedMps", speedMps);
        Contracts.finiteOrNaN("bearingDeg", bearingDeg);
        if (!Float.isNaN(accuracyMeters) && accuracyMeters < 0f) {
            throw new IllegalArgumentException("accuracyMeters must be >= 0 when provided, got " + accuracyMeters);
        }
        if (!Float.isNaN(speedMps) && speedMps < 0f) {
            throw new IllegalArgumentException("speedMps must be >= 0 when provided, got " + speedMps);
        }
    }

    public boolean speedAvailable() {
        return !Float.isNaN(speedMps);
    }

    public boolean bearingAvailable() {
        return !Float.isNaN(bearingDeg);
    }

    public boolean accuracyAvailable() {
        return !Float.isNaN(accuracyMeters);
    }
}
