package kz.zholsafe.config;

import java.util.Objects;

/**
 * Root configuration object. Assembled once at start-up (from defaults, then optional overrides
 * such as a bundled JSON/properties file or debug UI) and passed by reference to components.
 * Components must not read global state.
 *
 * @param mode      LIVE or DEMO — same pipeline, different frame source
 */
public record ZholSafeConfig(
        OperatingMode mode,
        DetectorConfig detector,
        TrackingConfig tracking,
        RiskConfig risk,
        NetworkConfig network,
        boolean driverGuardEnabled,
        boolean zholNetEnabled) {

    public enum OperatingMode { LIVE, DEMO }

    public ZholSafeConfig {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(detector, "detector");
        Objects.requireNonNull(tracking, "tracking");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(network, "network");
    }

    public static ZholSafeConfig defaults(OperatingMode mode) {
        return new ZholSafeConfig(mode, DetectorConfig.defaults(), TrackingConfig.defaults(),
                RiskConfig.defaults(), NetworkConfig.defaults(), true, true);
    }
}
