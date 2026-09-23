package kz.zholsafe.driver;

import kz.zholsafe.model.Contracts;

/**
 * DATA CONTRACT: bounded-window, TIME-WEIGHTED PERCLOS-LIKE engineering metric.
 *
 * <p>{@code value} = valid closed-eye time / valid observed-eye time inside a recent bounded
 * window, computed from DRIVER SOURCE TIMESTAMPS and segment durations — never from frame counts,
 * because frame intervals can vary. Only {@link EyeState#CLOSED} segments count as closed;
 * UNKNOWN/unobserved segments contribute to neither numerator nor denominator
 * ("missing != open, missing != closed").
 *
 * <p>When valid coverage is insufficient the metric is explicitly UNAVAILABLE ({@code value} is
 * NaN) — it is never reported as 0.0, which would falsely claim "eyes observed open".
 *
 * <p>This is an engineering fatigue-adjacent indicator with EXPERIMENTAL thresholds. It carries no
 * medical meaning.
 *
 * @param available                     enough valid coverage exists inside the window
 * @param value                         closed/valid fraction in [0,1]; MUST be NaN when unavailable
 * @param validObservationDurationNanos eye-observed segment time inside the window
 * @param windowDurationNanos           effective window span (bounded by the configured window and
 *                                      by the oldest retained observation)
 */
public record PerclosValue(boolean available, float value,
                           long validObservationDurationNanos, long windowDurationNanos) {

    public PerclosValue {
        Contracts.nonNegative("validObservationDurationNanos", validObservationDurationNanos);
        Contracts.nonNegative("windowDurationNanos", windowDurationNanos);
        if (validObservationDurationNanos > windowDurationNanos) {
            throw new IllegalArgumentException("valid observation time cannot exceed the window");
        }
        if (available) {
            Contracts.unit("PERCLOS value", value);
            if (windowDurationNanos <= 0L || validObservationDurationNanos <= 0L) {
                throw new IllegalArgumentException("available PERCLOS requires positive time spans");
            }
        } else if (!Float.isNaN(value)) {
            throw new IllegalArgumentException(
                    "PERCLOS value must be NaN when unavailable (no fabricated measurements)");
        }
    }

    /** Explicit "insufficient coverage" result; the reported span/coverage stay informative. */
    public static PerclosValue unavailable(long validObservationDurationNanos, long windowDurationNanos) {
        return new PerclosValue(false, Float.NaN, validObservationDurationNanos, windowDurationNanos);
    }
}
