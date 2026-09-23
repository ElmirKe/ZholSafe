package kz.zholsafe.driver;

import kz.zholsafe.model.Contracts;

import java.util.Objects;

/**
 * Raw per-frame output of a {@link kz.zholsafe.ai.DriverDetector}. No temporal reasoning.
 *
 * <p>Fields that a given detector cannot produce must be reported as unavailable
 * ({@code eyesClosedAvailable=false}, {@link HeadPose#UNAVAILABLE}, etc.) rather than guessed.
 *
 * @param faceDetected        whether a face was found
 * @param eyesClosedAvailable whether the eye state could be evaluated this frame
 * @param eyesClosed          both eyes judged closed (only meaningful if eyesClosedAvailable)
 * @param eyeOpenness         optional continuous openness in [0,1]; NaN if not produced
 * @param headPose            head pose or {@link HeadPose#UNAVAILABLE}
 * @param yawnAvailable       whether mouth/yawn state could be evaluated
 * @param yawning             mouth open in a yawn pattern (only meaningful if yawnAvailable)
 * @param confidence          detector confidence in [0,1] for the face/landmarks
 * @param timestampNanos      frame timestamp
 */
public record DriverObservation(
        boolean faceDetected,
        boolean eyesClosedAvailable,
        boolean eyesClosed,
        float eyeOpenness,
        HeadPose headPose,
        boolean yawnAvailable,
        boolean yawning,
        float confidence,
        long timestampNanos) {

    public DriverObservation {
        Objects.requireNonNull(headPose, "headPose");
        Contracts.unit("confidence", confidence);
        Contracts.finiteOrNaN("eyeOpenness", eyeOpenness);
        if (!Float.isNaN(eyeOpenness)) {
            Contracts.unit("eyeOpenness", eyeOpenness);
        }
    }

    /** Observation for "no face visible" — everything else unavailable. */
    public static DriverObservation noFace(long timestampNanos) {
        return new DriverObservation(false, false, false, Float.NaN, HeadPose.UNAVAILABLE,
                false, false, 0f, timestampNanos);
    }
}
