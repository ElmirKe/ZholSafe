package kz.zholsafe.config;

/**
 * DriverGuard thresholds.
 *
 * <p><b>EXPERIMENTAL / DEMO VALUES.</b> The defaults below are engineering placeholders chosen
 * to make the demo pipeline react visibly. They are NOT validated medical, ergonomic or
 * regulatory fatigue thresholds and must not be presented as such. They are expected to be
 * tuned (or replaced by calibrated values) in Stage 3/7.
 *
 * @param prolongedEyeClosureMillis continuous eye closure considered "prolonged" (demo value)
 * @param perclosWindowMillis       sliding window over which PERCLOS is computed (demo value)
 * @param perclosWarningFraction    PERCLOS fraction that raises DRIVER_HIGH_PERCLOS (demo value)
 * @param yawnRecentWindowMillis    how long a detected yawn stays "recent" (demo value)
 * @param minFaceConfidence         below this the observation is treated as no-face
 */
public record DriverGuardConfig(
        long prolongedEyeClosureMillis,
        long perclosWindowMillis,
        float perclosWarningFraction,
        long yawnRecentWindowMillis,
        float minFaceConfidence) {

    public static DriverGuardConfig defaults() {
        return new DriverGuardConfig(
                1500L,   // EXPERIMENTAL demo threshold
                60_000L, // EXPERIMENTAL demo window
                0.30f,   // EXPERIMENTAL demo fraction
                10_000L, // EXPERIMENTAL demo window
                0.50f);
    }
}
