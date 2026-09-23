package kz.zholsafe.driver;

import kz.zholsafe.model.Contracts;

import java.util.Objects;

/**
 * DATA CONTRACT: temporally-aggregated driver condition produced by {@link DrowsinessAnalyzer}.
 *
 * <p>All numeric fields carry an explicit availability flag or use a sentinel documented here,
 * so that missing data is never mistaken for "driver is fine".
 *
 * <p>Invariants (constructor-enforced): {@code eyeClosureDurationMillis >= 0};
 * {@code confidence} finite in [0,1]; when {@code perclosAvailable} the {@code perclos} value is
 * finite in [0,1]; when not available it MUST be the {@code Float.NaN} sentinel (a numeric value
 * with the flag off would be a fabricated measurement and is rejected).
 *
 * @param faceDetected             face visible in the latest observation
 * @param eyesClosed               eyes judged closed in the latest observation (false if unknown)
 * @param eyeClosureDurationMillis continuous eye-closure duration so far; 0 if eyes open/unknown
 * @param perclosAvailable         whether enough window data exists to compute PERCLOS
 * @param perclos                  fraction of time eyes closed over the configured window, [0,1]
 * @param headPose                 latest head pose or {@link HeadPose#UNAVAILABLE}
 * @param yawningDetected          yawn detected within the configured recent window
 * @param confidence               confidence of the latest observation, [0,1]
 * @param timestampNanos           timestamp of the latest observation
 */
public record DriverState(
        boolean faceDetected,
        boolean eyesClosed,
        long eyeClosureDurationMillis,
        boolean perclosAvailable,
        float perclos,
        HeadPose headPose,
        boolean yawningDetected,
        float confidence,
        long timestampNanos) {

    public DriverState {
        Objects.requireNonNull(headPose, "headPose");
        Contracts.nonNegative("eyeClosureDurationMillis", eyeClosureDurationMillis);
        Contracts.unit("confidence", confidence);
        if (perclosAvailable) {
            Contracts.unit("perclos", perclos);
        } else if (!Float.isNaN(perclos)) {
            throw new IllegalArgumentException(
                    "perclos must be NaN when perclosAvailable == false (no fabricated measurements), got " + perclos);
        }
    }

    /** State to use when DriverGuard is disabled or has produced nothing yet. */
    public static DriverState unavailable(long timestampNanos) {
        return new DriverState(false, false, 0L, false, Float.NaN, HeadPose.UNAVAILABLE,
                false, 0f, timestampNanos);
    }
}
