package kz.zholsafe.model;

/**
 * Provenance of an {@link Estimate}. Lets the Risk Engine, UI and logs state honestly how a
 * distance/TTC figure was obtained, and lets estimators be replaced without touching consumers.
 */
public enum EstimationMethod {
    NOT_AVAILABLE,
    /** Uncalibrated monocular heuristic (e.g. bounding-box height vs. assumed object size). Coarse. */
    MONOCULAR_UNCALIBRATED,
    /** Monocular with camera intrinsics/extrinsics calibration. */
    MONOCULAR_CALIBRATED,
    STEREO,
    RADAR,
    LIDAR,
    /** Derived from vehicle bus / OBD / GNSS speed and other estimates. */
    VEHICLE_SENSOR,
    /** Derived purely from image-space scale change over time (no metric distance). */
    SCALE_CHANGE
}
