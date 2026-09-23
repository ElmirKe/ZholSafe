package kz.zholsafe.model;

/**
 * Shared argument checks for data contracts. Centralised so that every record rejects NaN and
 * infinite values in the same way.
 *
 * <p>Policy: a value that is <em>required</em> or declared <em>available</em> must be finite and
 * in range. NaN is permitted only as the documented "unavailable" sentinel of fields that carry
 * an explicit availability flag (see {@code docs/DATA_CONTRACTS.md}).
 */
public final class Contracts {

    private Contracts() { }

    /** Requires a finite value in [0,1]. */
    public static float unit(String name, float v) {
        finite(name, v);
        if (v < 0f || v > 1f) {
            throw new IllegalArgumentException(name + " must be in [0,1], got " + v);
        }
        return v;
    }

    public static float finite(String name, float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            throw new IllegalArgumentException(name + " must be finite, got " + v);
        }
        return v;
    }

    public static double finite(String name, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException(name + " must be finite, got " + v);
        }
        return v;
    }

    public static double range(String name, double v, double min, double max) {
        finite(name, v);
        if (v < min || v > max) {
            throw new IllegalArgumentException(name + " must be in [" + min + "," + max + "], got " + v);
        }
        return v;
    }

    public static float nonNegative(String name, float v) {
        finite(name, v);
        if (v < 0f) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + v);
        }
        return v;
    }

    public static long nonNegative(String name, long v) {
        if (v < 0L) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + v);
        }
        return v;
    }

    public static int positive(String name, int v) {
        if (v <= 0) {
            throw new IllegalArgumentException(name + " must be > 0, got " + v);
        }
        return v;
    }

    public static long positive(String name, long v) {
        if (v <= 0L) {
            throw new IllegalArgumentException(name + " must be > 0, got " + v);
        }
        return v;
    }

    /** For optional fields: either the documented NaN sentinel, or a finite value. */
    public static float finiteOrNaN(String name, float v) {
        if (Float.isInfinite(v)) {
            throw new IllegalArgumentException(name + " must be finite or NaN (unavailable), got " + v);
        }
        return v;
    }
}
