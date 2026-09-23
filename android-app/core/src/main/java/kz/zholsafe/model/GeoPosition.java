package kz.zholsafe.model;

/**
 * GNSS fix. {@code speedMps} and {@code bearingDeg} are optional: {@link #speedAvailable()} and
 * {@link #bearingAvailable()} must be checked before use.
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
