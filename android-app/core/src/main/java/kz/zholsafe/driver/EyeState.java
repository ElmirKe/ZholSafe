package kz.zholsafe.driver;

/**
 * Qualitative per-observation eye state derived in the temporal analyzer from continuous per-eye
 * openness and the configurable thresholds of {@link kz.zholsafe.config.DriverGuardConfig}.
 *
 * <p>{@link #UNKNOWN} means "no usable eye evidence" (face missing, eyes occluded, landmarks or
 * confidence insufficient). UNKNOWN is never silently counted as OPEN or CLOSED, in neither the
 * continuous-closure run nor the PERCLOS denominator.
 *
 * <p>Drowsiness is NEVER inferred from a single frame — that judgement belongs to the temporal
 * analyzer and the driver risk policy.
 */
public enum EyeState {
    OPEN,
    PARTIALLY_CLOSED,
    CLOSED,
    UNKNOWN
}
