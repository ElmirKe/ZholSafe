package kz.zholsafe.config;

import kz.zholsafe.model.Contracts;

/**
 * DriverGuard thresholds — engineering knobs for the temporal driver-state analyzer
 * ({@code TemporalDriverStateAnalyzer}) and the driver-only risk policy ({@code DriverRiskEngine}).
 *
 * <p><b>ALL VALUES ARE EXPERIMENTAL / NOT SAFETY VALIDATED.</b> The defaults below are demo
 * placeholders chosen so the pipeline reacts visibly in the Stage 4.3 deterministic demo. They are
 * NOT validated medical, ergonomic or regulatory thresholds. In particular, the prolonged-closure
 * WARNING threshold of ~1.5 s is an <b>EXPERIMENTAL DEMO THRESHOLD</b> that triggers the
 * PROLONGED_EYE_CLOSURE evidence code — it is NOT a universal or clinical definition of
 * microsleep, and ZholSafe never emits a medical diagnosis (no "drowsy driver" claims).
 *
 * @param eyeClosedThreshold              openness at/below which BOTH eyes count as CLOSED
 *                                        (classification uses min(left, right) openness)
 * @param eyePartiallyClosedThreshold     openness at/below which eyes count PARTIALLY_CLOSED;
 *                                        must exceed {@code eyeClosedThreshold}
 * @param minimumObservationConfidence    below this, face/eye/mouth/pose evidence is not trusted
 *                                        for temporal accumulation (observation becomes UNKNOWN)
 * @param prolongedClosureCautionSeconds  continuous source-time closure for CAUTION evidence
 * @param prolongedClosureWarningSeconds  continuous closure for WARNING evidence
 *                                        (EXPERIMENTAL DEMO THRESHOLD, ~1.5 s in the demo)
 * @param prolongedClosureCriticalSeconds continuous closure for CRITICAL evidence
 * @param perclosWindowSeconds            bounded recent source-time window of the PERCLOS-like metric
 * @param minimumPerclosValidCoverage     minimum valid-observed/window fraction for PERCLOS availability
 * @param perclosCautionThreshold         PERCLOS fraction for CAUTION evidence
 * @param perclosWarningThreshold         PERCLOS fraction for WARNING evidence
 * @param mouthOpenThreshold              mouth-open score at/above which the mouth counts as open
 * @param minimumYawnDurationSeconds      persistence required for a YAWN_LIKE event
 * @param headYawThresholdDegrees         |yaw| at/above which the head counts as LEFT/RIGHT
 * @param headDownPitchThresholdDegrees   pitch at/above which the head counts as DOWN
 * @param headAwayDurationSeconds         persistence required for head-away/down evidence
 * @param persistentFaceLossSeconds       face-missing persistence for DRIVER_VISIBILITY_LOST evidence
 * @param eyeVisibilityLostSeconds        eyes-not-evaluable persistence for INSUFFICIENT_EYE_VISIBILITY
 * @param maximumObservationGapSeconds    source-time gap after which continuity runs break;
 *                                        neither OPEN nor CLOSED is assumed across it
 * @param maxObservations                 hard bound on retained observation entries (memory safety
 *                                        net beyond time-based window eviction)
 */
public record DriverGuardConfig(
        float eyeClosedThreshold,
        float eyePartiallyClosedThreshold,
        float minimumObservationConfidence,
        double prolongedClosureCautionSeconds,
        double prolongedClosureWarningSeconds,
        double prolongedClosureCriticalSeconds,
        double perclosWindowSeconds,
        float minimumPerclosValidCoverage,
        float perclosCautionThreshold,
        float perclosWarningThreshold,
        float mouthOpenThreshold,
        double minimumYawnDurationSeconds,
        float headYawThresholdDegrees,
        float headDownPitchThresholdDegrees,
        double headAwayDurationSeconds,
        double persistentFaceLossSeconds,
        double eyeVisibilityLostSeconds,
        double maximumObservationGapSeconds,
        int maxObservations) {

    public DriverGuardConfig {
        Contracts.unit("eyeClosedThreshold", eyeClosedThreshold);
        Contracts.unit("eyePartiallyClosedThreshold", eyePartiallyClosedThreshold);
        if (eyeClosedThreshold >= eyePartiallyClosedThreshold) {
            throw new IllegalArgumentException("eyeClosedThreshold must be < eyePartiallyClosedThreshold");
        }
        Contracts.unit("minimumObservationConfidence", minimumObservationConfidence);
        Contracts.finite("prolongedClosureCautionSeconds", prolongedClosureCautionSeconds);
        Contracts.finite("prolongedClosureWarningSeconds", prolongedClosureWarningSeconds);
        Contracts.finite("prolongedClosureCriticalSeconds", prolongedClosureCriticalSeconds);
        if (!(prolongedClosureCautionSeconds < prolongedClosureWarningSeconds
                && prolongedClosureWarningSeconds < prolongedClosureCriticalSeconds)) {
            throw new IllegalArgumentException("closure thresholds must be caution < warning < critical");
        }
        Contracts.finite("perclosWindowSeconds", perclosWindowSeconds);
        if (perclosWindowSeconds <= 0d) {
            throw new IllegalArgumentException("perclosWindowSeconds must be > 0");
        }
        Contracts.unit("minimumPerclosValidCoverage", minimumPerclosValidCoverage);
        if (minimumPerclosValidCoverage <= 0f) {
            throw new IllegalArgumentException("minimumPerclosValidCoverage must be > 0");
        }
        Contracts.unit("perclosCautionThreshold", perclosCautionThreshold);
        Contracts.unit("perclosWarningThreshold", perclosWarningThreshold);
        if (perclosCautionThreshold > perclosWarningThreshold) {
            throw new IllegalArgumentException("perclosCautionThreshold must be <= perclosWarningThreshold");
        }
        Contracts.unit("mouthOpenThreshold", mouthOpenThreshold);
        Contracts.finite("minimumYawnDurationSeconds", minimumYawnDurationSeconds);
        Contracts.finite("headAwayDurationSeconds", headAwayDurationSeconds);
        Contracts.finite("persistentFaceLossSeconds", persistentFaceLossSeconds);
        Contracts.finite("eyeVisibilityLostSeconds", eyeVisibilityLostSeconds);
        Contracts.finite("maximumObservationGapSeconds", maximumObservationGapSeconds);
        if (minimumYawnDurationSeconds <= 0d || headAwayDurationSeconds <= 0d
                || persistentFaceLossSeconds <= 0d || eyeVisibilityLostSeconds <= 0d
                || maximumObservationGapSeconds <= 0d) {
            throw new IllegalArgumentException("duration thresholds must be > 0");
        }
        Contracts.range("headYawThresholdDegrees", headYawThresholdDegrees, 0d, 90d);
        Contracts.range("headDownPitchThresholdDegrees", headDownPitchThresholdDegrees, 0d, 90d);
        if (maxObservations < 2) {
            throw new IllegalArgumentException("maxObservations must be >= 2");
        }
    }

    /** EXPERIMENTAL demo defaults — see class javadoc; NOT safety validated. */
    public static DriverGuardConfig defaults() {
        return new DriverGuardConfig(
                0.30f,   // eye-closed openness — EXPERIMENTAL
                0.60f,   // partially-closed openness — EXPERIMENTAL
                0.50f,   // minimum observation confidence — EXPERIMENTAL
                0.70d,   // prolonged closure CAUTION — EXPERIMENTAL
                1.50d,   // prolonged closure WARNING — EXPERIMENTAL DEMO THRESHOLD (not a microsleep definition)
                3.00d,   // prolonged closure CRITICAL — EXPERIMENTAL
                60.0d,   // PERCLOS window — EXPERIMENTAL
                0.50f,   // minimum PERCLOS valid coverage — EXPERIMENTAL
                0.15f,   // PERCLOS CAUTION — EXPERIMENTAL
                0.30f,   // PERCLOS WARNING — EXPERIMENTAL
                0.60f,   // mouth-open score — EXPERIMENTAL
                2.0d,    // yawn-like persistence — EXPERIMENTAL
                30.0f,   // head yaw threshold — EXPERIMENTAL
                25.0f,   // head-down pitch threshold — EXPERIMENTAL
                2.0d,    // head-away persistence — EXPERIMENTAL
                5.0d,    // persistent face loss — EXPERIMENTAL
                5.0d,    // eye visibility lost — EXPERIMENTAL
                1.0d,    // maximum observation gap — EXPERIMENTAL
                4096);   // hard bound on retained observation entries
    }

    // ---- legacy Stage 0 shims (BaselineRiskEngine compatibility) ----

    /** Legacy accessor: warning-level prolonged closure in whole milliseconds. */
    public long prolongedEyeClosureMillis() {
        return Math.round(prolongedClosureWarningSeconds * 1000d);
    }

    /** Legacy accessor for {@link #perclosWarningThreshold()}. */
    public float perclosWarningFraction() {
        return perclosWarningThreshold;
    }
}
