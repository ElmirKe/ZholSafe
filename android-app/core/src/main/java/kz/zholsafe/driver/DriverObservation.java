package kz.zholsafe.driver;

import kz.zholsafe.model.Contracts;

import java.util.Objects;

/**
 * DATA CONTRACT: raw per-frame output of a {@link DriverObservationProvider}. No temporal
 * reasoning happens here — one frame, one observation.
 *
 * <p>Stage 4.3 contract: continuous per-eye openness replaces the Stage 0 binary
 * {@code eyesClosed} flag and a continuous mouth-open score replaces the binary {@code yawning}
 * flag, so that all qualitative thresholds live in
 * {@link kz.zholsafe.config.DriverGuardConfig} and stay configurable/tunable.
 *
 * <p>Unavailability is explicit: values a provider cannot measure are {@code Float.NaN} with the
 * matching {@code *Available} flag set to false. A real measured {@code 0.0f} is NEVER a
 * substitute for "unavailable" — {@code 0.0f} openness means "eye measured fully closed".
 * No {@link kz.zholsafe.ai.Frame}, image, bitmap or tensor is retained — scalars only.
 *
 * @param timestampNanos       source/image capture time of the frame (same-source monotonic,
 *                             never wall-clock, never the processing clock)
 * @param faceDetected         whether a face was found at all
 * @param eyeOpennessAvailable whether both eye-openness values are real measurements this frame
 * @param leftEyeOpenness      measured left-eye openness in [0,1]; NaN iff not available
 * @param rightEyeOpenness     measured right-eye openness in [0,1]; NaN iff not available
 * @param mouthAvailable       whether the mouth-open score is a real measurement this frame
 * @param mouthOpenScore       measured mouth-open score in [0,1] (yawn-LIKE engineering signal,
 *                             not a medical yawn diagnosis); NaN iff not available
 * @param headPose             head orientation or {@link HeadPose#UNAVAILABLE}
 * @param confidence           provider confidence for the face/landmark result, in [0,1]
 */
public record DriverObservation(
        long timestampNanos,
        boolean faceDetected,
        boolean eyeOpennessAvailable,
        float leftEyeOpenness,
        float rightEyeOpenness,
        boolean mouthAvailable,
        float mouthOpenScore,
        HeadPose headPose,
        float confidence) {

    public DriverObservation {
        if (timestampNanos < 0L) {
            throw new IllegalArgumentException("negative source time");
        }
        Objects.requireNonNull(headPose, "headPose");
        Contracts.unit("confidence", confidence);
        if (eyeOpennessAvailable) {
            Contracts.unit("leftEyeOpenness", leftEyeOpenness);
            Contracts.unit("rightEyeOpenness", rightEyeOpenness);
        } else if (!Float.isNaN(leftEyeOpenness) || !Float.isNaN(rightEyeOpenness)) {
            throw new IllegalArgumentException(
                    "eye openness values must be NaN when unavailable (no fabricated measurements)");
        }
        if (mouthAvailable) {
            Contracts.unit("mouthOpenScore", mouthOpenScore);
        } else if (!Float.isNaN(mouthOpenScore)) {
            throw new IllegalArgumentException(
                    "mouthOpenScore must be NaN when unavailable (no fabricated measurements)");
        }
        if (!faceDetected && (eyeOpennessAvailable || mouthAvailable || headPose.available())) {
            throw new IllegalArgumentException("face-derived measurements cannot exist without a face");
        }
    }

    /** Canonical "nothing seen" observation: everything unavailable, confidence 0. */
    public static DriverObservation noFace(long timestampNanos) {
        return new DriverObservation(timestampNanos, false, false, Float.NaN, Float.NaN,
                false, Float.NaN, HeadPose.UNAVAILABLE, 0f);
    }

    /** Same measurements re-tagged to a different source timestamp (synthetic/replay providers). */
    public DriverObservation withTimestamp(long newTimestampNanos) {
        return new DriverObservation(newTimestampNanos, faceDetected, eyeOpennessAvailable,
                leftEyeOpenness, rightEyeOpenness, mouthAvailable, mouthOpenScore,
                headPose, confidence);
    }
}
