package kz.zholsafe.driver;

import kz.zholsafe.model.Contracts;

import java.util.Objects;

/**
 * DATA CONTRACT: temporally-aggregated driver condition produced by a {@link DriverStateAnalyzer}
 * from a stream of {@link DriverObservation}s. Every duration derives from DRIVER SOURCE
 * TIMESTAMPS — never from frame counts and never from the processing clock.
 *
 * <p>Missing evidence stays explicit: UNKNOWN states are never silently OPEN/CLOSED or FORWARD,
 * unavailable numeric values use the documented NaN sentinel (inside {@link PerclosValue}) and
 * {@code 0} is always a real measured quantity. This is an ENGINEERING signal bundle, not a
 * medical or clinical statement about the driver.
 *
 * <p>Coherence invariants (constructor-enforced): positive eye-closure duration requires CLOSED
 * eyes; without a face, eye state is UNKNOWN, head direction is UNKNOWN, the raw pose is
 * unavailable and yawn-like evidence is UNAVAILABLE — no fabricated face-derived state.
 *
 * @param timestampNanos                latest ACCEPTED observation source time (rejections do not move it)
 * @param faceDetected                  face visible in the latest accepted observation
 * @param eyeState                      latest qualitative eye state (UNKNOWN when not evaluable)
 * @param continuousEyeClosureNanos     source-time duration of the on-going consecutive CLOSED run;
 *                                      0 unless the latest accepted state is CLOSED
 * @param perclos                       bounded-window time-weighted PERCLOS-like metric
 * @param headPoseState                 latest qualitative head direction (UNKNOWN when not evaluable)
 * @param continuousHeadAwayNanos       source-time duration of the on-going LEFT/RIGHT/DOWN run
 * @param headPose                      latest raw orientation or {@link HeadPose#UNAVAILABLE}
 * @param yawnLikeState                 engineering yawn-like persistence state
 * @param continuousYawnLikeNanos       source-time duration of the on-going mouth-open run
 * @param continuousFaceLossNanos       source-time duration of the on-going no-face run
 * @param continuousEyeUnavailableNanos source-time duration of the face-visible-but-eyes-not-evaluable run
 * @param observationQuality            engineering grade of the latest accepted observation
 * @param confidence                    latest observation confidence in [0,1]
 * @param timestampRejection            outcome of the monotonicity check of the most recent observation
 */
public record DriverState(
        long timestampNanos,
        boolean faceDetected,
        EyeState eyeState,
        long continuousEyeClosureNanos,
        PerclosValue perclos,
        HeadPoseState headPoseState,
        long continuousHeadAwayNanos,
        HeadPose headPose,
        YawnLikeState yawnLikeState,
        long continuousYawnLikeNanos,
        long continuousFaceLossNanos,
        long continuousEyeUnavailableNanos,
        ObservationQuality observationQuality,
        float confidence,
        TimestampRejection timestampRejection) {

    /** Outcome of the monotonicity check for the most recent observation's source timestamp. */
    public enum TimestampRejection {
        /** Accepted (or nothing received yet). */
        NONE,
        /** Same source timestamp as the previously accepted observation — rejected. */
        DUPLICATE_TIMESTAMP,
        /** Older than the previously accepted observation — rejected. */
        REVERSED_TIMESTAMP
    }

    public DriverState {
        if (timestampNanos < 0L) {
            throw new IllegalArgumentException("negative source time");
        }
        Objects.requireNonNull(eyeState, "eyeState");
        Objects.requireNonNull(perclos, "perclos");
        Objects.requireNonNull(headPoseState, "headPoseState");
        Objects.requireNonNull(headPose, "headPose");
        Objects.requireNonNull(yawnLikeState, "yawnLikeState");
        Objects.requireNonNull(observationQuality, "observationQuality");
        Objects.requireNonNull(timestampRejection, "timestampRejection");
        Contracts.nonNegative("continuousEyeClosureNanos", continuousEyeClosureNanos);
        Contracts.nonNegative("continuousHeadAwayNanos", continuousHeadAwayNanos);
        Contracts.nonNegative("continuousYawnLikeNanos", continuousYawnLikeNanos);
        Contracts.nonNegative("continuousFaceLossNanos", continuousFaceLossNanos);
        Contracts.nonNegative("continuousEyeUnavailableNanos", continuousEyeUnavailableNanos);
        Contracts.unit("confidence", confidence);
        if (eyeState != EyeState.CLOSED && continuousEyeClosureNanos > 0L) {
            throw new IllegalArgumentException("positive eye-closure duration requires CLOSED eyes");
        }
        if (!faceDetected) {
            if (eyeState != EyeState.UNKNOWN || headPoseState != HeadPoseState.UNKNOWN
                    || yawnLikeState != YawnLikeState.UNAVAILABLE || headPose.available()) {
                throw new IllegalArgumentException("no face cannot carry face-derived measurements");
            }
        }
    }

    /** State to use when DriverGuard is disabled or has produced nothing yet. */
    public static DriverState unavailable(long timestampNanos) {
        return new DriverState(timestampNanos, false, EyeState.UNKNOWN, 0L,
                PerclosValue.unavailable(0L, 0L), HeadPoseState.UNKNOWN, 0L, HeadPose.UNAVAILABLE,
                YawnLikeState.UNAVAILABLE, 0L, 0L, 0L, ObservationQuality.UNAVAILABLE, 0f,
                TimestampRejection.NONE);
    }

    /** Copy of {@code base} with a different rejection flag; all accepted data remains untouched. */
    public static DriverState withRejection(DriverState base, TimestampRejection rejection) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(rejection, "rejection");
        return new DriverState(base.timestampNanos(), base.faceDetected(), base.eyeState(),
                base.continuousEyeClosureNanos(), base.perclos(), base.headPoseState(),
                base.continuousHeadAwayNanos(), base.headPose(), base.yawnLikeState(),
                base.continuousYawnLikeNanos(), base.continuousFaceLossNanos(),
                base.continuousEyeUnavailableNanos(), base.observationQuality(), base.confidence(),
                rejection);
    }

    // ---- legacy Stage 0 accessors (BaselineRiskEngine compatibility) ----

    /** Legacy accessor: both eyes judged closed in the latest accepted observation. */
    public boolean eyesClosed() {
        return eyeState == EyeState.CLOSED;
    }

    /** Legacy accessor in whole milliseconds (truncated). Prefer {@link #continuousEyeClosureNanos()}. */
    public long eyeClosureDurationMillis() {
        return continuousEyeClosureNanos / 1_000_000L;
    }

    /** Legacy accessor for {@link PerclosValue#available()}. */
    public boolean perclosAvailable() {
        return perclos.available();
    }

    /** Legacy accessor: true while the mouth-open run persisted past the yawn-like threshold. */
    public boolean yawningDetected() {
        return yawnLikeState == YawnLikeState.YAWN_LIKE;
    }
}
