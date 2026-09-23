package kz.zholsafe.ai;

/** Lifecycle state of a {@link RoadDetector}. Exposed to telemetry/UI; never inferred from detections. */
public enum DetectorState {
    NOT_LOADED,
    LOADING,
    READY,
    /** Model missing, incompatible, or repeated fatal failure. Detections are UNAVAILABLE, not "none". */
    ERROR,
    CLOSED
}
