package kz.zholsafe.model;

import java.util.Objects;

/**
 * A numeric quantity that may or may not be available, and which is always an ESTIMATE.
 *
 * <p>Used for distance (metres) and time-to-collision (seconds). ZholSafe never implies false
 * measurement precision: every consumer must check {@link #available()} before using
 * {@link #value()}, and UI/logging must label the value with {@link #method()}.
 *
 * @param available whether a value could be estimated at all
 * @param value     the estimated value; only meaningful when {@code available} is true
 * @param method    how the value was produced (never null)
 */
public record Estimate(boolean available, double value, EstimationMethod method) {

    private static final Estimate UNAVAILABLE = new Estimate(false, Double.NaN, EstimationMethod.NOT_AVAILABLE);

    public Estimate {
        Objects.requireNonNull(method, "method");
        if (available && (Double.isNaN(value) || Double.isInfinite(value))) {
            throw new IllegalArgumentException("available estimate must have a finite value");
        }
        if (!available && method != EstimationMethod.NOT_AVAILABLE) {
            throw new IllegalArgumentException("unavailable estimate must use EstimationMethod.NOT_AVAILABLE");
        }
    }

    /** The canonical "not available" value. Prefer this over fabricating numbers. */
    public static Estimate unavailable() {
        return UNAVAILABLE;
    }

    public static Estimate of(double value, EstimationMethod method) {
        if (method == EstimationMethod.NOT_AVAILABLE) {
            throw new IllegalArgumentException("use Estimate.unavailable()");
        }
        return new Estimate(true, value, method);
    }

    /** Returns the value or the given fallback when unavailable. */
    public double orElse(double fallback) {
        return available ? value : fallback;
    }
}
