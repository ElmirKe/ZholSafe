package kz.zholsafe.model;

import java.util.Objects;

/**
 * A numeric quantity that may or may not be available, and which is always an ESTIMATE.
 *
 * <p>Used for distance (metres) and time-to-collision (seconds). ZholSafe never implies false
 * measurement precision: every consumer must check {@link #available()} before using
 * {@link #value()}, and UI/logging must label the value with {@link #method()}.
 *
 * <h2>Value semantics (contract, decided in Stage 0.1)</h2>
 * <ul>
 *   <li>An available estimate is always <b>finite</b> (NaN/Infinity rejected).</li>
 *   <li><b>Distance</b> is a physical magnitude: use {@link #distance(double, EstimationMethod)},
 *       which rejects negative values. A negative "distance" is an estimator bug, not data.</li>
 *   <li><b>TTC</b>: use {@link #ttc(double, EstimationMethod)}. ZholSafe defines TTC as the
 *       estimated time <em>until</em> a predicted collision; it is therefore <b>non-negative</b>.
 *       A mathematically negative TTC (closing speed ≤ 0, or closest approach already passed)
 *       carries no forward-looking collision information, so estimators MUST return
 *       {@link #unavailable()} instead of a negative value. {@link #ttc} enforces this by
 *       rejecting negatives, so the Risk Engine can never see a negative TTC and misread it as
 *       "below the low-TTC threshold". The Risk Engine additionally guards against it.</li>
 *   <li>{@link #of(double, EstimationMethod)} is the generic constructor for quantities without a
 *       sign constraint; prefer the typed factories above for distance and TTC.</li>
 * </ul>
 *
 * @param available whether a value could be estimated at all
 * @param value     the estimated value; only meaningful when {@code available} is true
 * @param method    how the value was produced (never null)
 */
public record Estimate(boolean available, double value, EstimationMethod method) {

    private static final Estimate UNAVAILABLE = new Estimate(false, Double.NaN, EstimationMethod.NOT_AVAILABLE);

    public Estimate {
        Objects.requireNonNull(method, "method");
        if (available) {
            Contracts.finite("value", value);
            if (method == EstimationMethod.NOT_AVAILABLE) {
                throw new IllegalArgumentException("available estimate must not use EstimationMethod.NOT_AVAILABLE");
            }
        } else {
            if (method != EstimationMethod.NOT_AVAILABLE) {
                throw new IllegalArgumentException("unavailable estimate must use EstimationMethod.NOT_AVAILABLE");
            }
            if (!Double.isNaN(value)) {
                throw new IllegalArgumentException("unavailable estimate must carry NaN, got " + value);
            }
        }
    }

    /** The canonical "not available" value. Prefer this over fabricating numbers. */
    public static Estimate unavailable() {
        return UNAVAILABLE;
    }

    /** Generic finite estimate without a sign constraint. */
    public static Estimate of(double value, EstimationMethod method) {
        return new Estimate(true, value, method);
    }

    /** Physical distance in metres; must be {@code >= 0}. */
    public static Estimate distance(double meters, EstimationMethod method) {
        Contracts.finite("distance", meters);
        if (meters < 0d) {
            throw new IllegalArgumentException("distance estimate must be >= 0, got " + meters);
        }
        return new Estimate(true, meters, method);
    }

    /**
     * Time-to-collision in seconds; must be {@code >= 0}. Estimators that compute a negative or
     * undefined TTC must return {@link #unavailable()} instead of calling this.
     */
    public static Estimate ttc(double seconds, EstimationMethod method) {
        Contracts.finite("ttc", seconds);
        if (seconds < 0d) {
            throw new IllegalArgumentException(
                    "ttc estimate must be >= 0 (negative TTC is not a valid estimate; return unavailable()), got " + seconds);
        }
        return new Estimate(true, seconds, method);
    }

    /** True when available and {@code value >= 0}. Convenience for physical magnitudes. */
    public boolean isNonNegative() {
        return available && value >= 0d;
    }

    /** Returns the value or the given fallback when unavailable. */
    public double orElse(double fallback) {
        return available ? value : fallback;
    }
}
