package kz.zholsafe.risk;

/**
 * Machine-readable explanation codes attached to every {@link RiskAssessment}.
 *
 * <p>The Risk Engine must NEVER emit a level without at least one reason for CAUTION and above.
 * UI, logs and ZholNet payloads use these codes; human-readable text is resolved in the UI layer.
 */
public enum RiskReason {
    // ---- driver ----
    DRIVER_FACE_NOT_DETECTED,
    DRIVER_EYES_CLOSED,
    DRIVER_PROLONGED_EYE_CLOSURE,
    DRIVER_HIGH_PERCLOS,
    DRIVER_YAWNING,
    DRIVER_HEAD_POSE_DISTRACTED,
    DRIVER_STATE_UNAVAILABLE,

    // ---- road hazard presence ----
    PERSON_DETECTED,
    ANIMAL_DETECTED,
    HORSE_DETECTED,
    COW_DETECTED,
    SHEEP_DETECTED,
    GOAT_DETECTED,
    CAMEL_DETECTED,
    DOG_DETECTED,
    UNKNOWN_OBJECT_DETECTED,
    MULTIPLE_HAZARDS_DETECTED,

    // ---- trajectory / geometry ----
    OBJECT_IN_DRIVING_CORRIDOR,
    OBJECT_APPROACHING_DRIVING_CORRIDOR,
    OBJECT_CLOSING,
    OBJECT_LARGE_IN_FRAME,

    // ---- collision ----
    LOW_ESTIMATED_DISTANCE,
    LOW_ESTIMATED_TTC,

    // ---- vehicle context ----
    HIGH_VEHICLE_SPEED,
    NIGHT_CONDITIONS,

    // ---- system ----
    ROAD_DETECTOR_UNAVAILABLE,
    LOW_CONFIDENCE_ONLY
}
