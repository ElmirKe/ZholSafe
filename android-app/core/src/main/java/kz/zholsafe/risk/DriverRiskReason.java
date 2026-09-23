package kz.zholsafe.risk;

/**
 * Machine-readable explanation codes for {@link DriverRiskSnapshot}. Every non-NORMAL driver risk
 * carries at least one (enforced by the {@link DriverRiskSnapshot} record). These are ENGINEERING
 * evidence codes — never a medical diagnosis; in particular nothing here claims "microsleep" or
 * "asleep driver" (prolonged eye closure is evidence, not a clinical finding).
 */
public enum DriverRiskReason {
    /** Eyes closed right now (any duration, including a normal blink — severity comes from persistence). */
    EYES_CLOSED,
    /** Continuous source-time closure crossed a configured prolonged-closure threshold
     *  (EXPERIMENTAL DEMO THRESHOLDS, not a microsleep definition). */
    PROLONGED_EYE_CLOSURE,
    /** Time-weighted windowed PERCLOS-like metric crossed a configured threshold. */
    HIGH_PERCLOS,
    /** Mouth-open evidence persisted past the configured duration (yawn-LIKE, not a medical yawn). */
    YAWN_LIKE_EVENT,
    /** Head turned LEFT/RIGHT persistently. */
    HEAD_AWAY,
    /** Head pitched DOWN persistently. */
    LOOKING_DOWN,
    /** No face is currently detected (any cause: darkness, occlusion, angle, frame exit, backend failure). */
    FACE_NOT_DETECTED,
    /** Face-missing persisted past the configured threshold — monitoring/visibility evidence,
     *  explicitly distinct from drowsiness evidence. */
    DRIVER_VISIBILITY_LOST,
    /** Face visible but eyes not evaluable persistently (occlusion, eyewear, low confidence). */
    INSUFFICIENT_EYE_VISIBILITY,
    /** Latest observation quality degraded/unavailable or source timestamps misbehaving (informative). */
    LOW_OBSERVATION_QUALITY,
    /** DriverGuard produced no usable state at all (informational). */
    DRIVER_STATE_UNAVAILABLE
}
