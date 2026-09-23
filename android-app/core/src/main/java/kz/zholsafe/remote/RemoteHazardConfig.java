package kz.zholsafe.remote;

import java.time.Duration;

/** EXPERIMENTAL MVP CONFIGURATION; these values are not regulatory safety thresholds. */
public record RemoteHazardConfig(double fetchRadiusMeters, Duration pollInterval,
        Duration requestTimeout, Duration maximumVehicleLocationAge, Duration maximumAdvisoryAge,
        double nearDistanceMeters, double mediumDistanceMeters, double forwardConeDegrees,
        double behindThresholdDegrees, Duration warningHoldTime, int maximumResults) {
    public RemoteHazardConfig {
        positive(fetchRadiusMeters, "fetchRadiusMeters");
        positive(nearDistanceMeters, "nearDistanceMeters");
        positive(mediumDistanceMeters, "mediumDistanceMeters");
        if (nearDistanceMeters >= mediumDistanceMeters || mediumDistanceMeters > fetchRadiusMeters) {
            throw new IllegalArgumentException("distance bands must increase within fetch radius");
        }
        if (!Double.isFinite(forwardConeDegrees) || forwardConeDegrees <= 0 || forwardConeDegrees >= 90
                || !Double.isFinite(behindThresholdDegrees) || behindThresholdDegrees <= 90
                || behindThresholdDegrees > 180) throw new IllegalArgumentException("invalid direction bands");
        duration(pollInterval, "pollInterval", true); duration(requestTimeout, "requestTimeout", true);
        duration(maximumVehicleLocationAge, "maximumVehicleLocationAge", true);
        duration(maximumAdvisoryAge, "maximumAdvisoryAge", true);
        duration(warningHoldTime, "warningHoldTime", false);
        if (maximumResults <= 0 || maximumResults > 100) throw new IllegalArgumentException("maximumResults must be 1..100");
    }
    public static RemoteHazardConfig defaults() {
        return new RemoteHazardConfig(2_000, Duration.ofSeconds(2), Duration.ofMillis(1500),
                Duration.ofSeconds(5), Duration.ofMinutes(2), 500, 1_200,
                60, 120, Duration.ofSeconds(3), 50);
    }
    private static void positive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
    private static void duration(Duration value, String name, boolean positive) {
        if (value == null || value.isNegative() || (positive && value.isZero())) {
            throw new IllegalArgumentException("invalid " + name);
        }
    }
}
