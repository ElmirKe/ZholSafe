package kz.zholsafe.driver;

/**
 * Qualitative head direction derived from {@link HeadPose} angles with the configurable yaw/pitch
 * thresholds of {@link kz.zholsafe.config.DriverGuardConfig}.
 *
 * <p>{@link #UNKNOWN} means "no usable pose evidence" and is never treated as FORWARD. A brief
 * head movement must not escalate risk — only persistence (tracked with source timestamps in the
 * analyzer) may contribute.
 */
public enum HeadPoseState {
    FORWARD,
    LEFT,
    RIGHT,
    DOWN,
    UNKNOWN;

    /** Head not oriented at the road ahead. */
    public boolean isAway() {
        return this == LEFT || this == RIGHT || this == DOWN;
    }
}
