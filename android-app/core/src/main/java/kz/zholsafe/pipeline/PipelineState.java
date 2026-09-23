package kz.zholsafe.pipeline;

/**
 * Coarse, user-visible state of each subsystem. Used so the UI can show honest status
 * ("Road model missing", "GPS unavailable", "ZholNet offline") instead of pretending.
 */
public enum PipelineState {
    NOT_STARTED,
    STARTING,
    RUNNING,
    DEGRADED,
    UNAVAILABLE,
    STOPPED
}
