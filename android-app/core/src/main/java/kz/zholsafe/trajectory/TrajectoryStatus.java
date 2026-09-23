package kz.zholsafe.trajectory;

/** Per-track evidence availability; only AVAILABLE permits numeric motion/scale output. */
public enum TrajectoryStatus {
    AVAILABLE, INSUFFICIENT_HISTORY, INVALID_TIMESTAMPS, LOW_QUALITY, TRACK_NOT_CURRENT
}
