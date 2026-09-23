package kz.zholsafe.physical;

import kz.zholsafe.model.Contracts;

import java.util.Objects;

/**
 * Estimated camera OPTICAL-AXIS depth in metres (not ground-path or slant distance). Ground-plane
 * and size estimates share this quantity. Quantified bounds exist ONLY for size priors; a ground
 * geometry estimate with unknown calibration uncertainty has boundsAvailable=false, not [d,d].
 */
public record DistanceEstimate(boolean available, double meters, boolean boundsAvailable,
        double lowerBoundMeters, double upperBoundMeters, DistanceMethod method,
        EvidenceQuality quality, long timestampNanos, PhysicalReason reason) {
    public DistanceEstimate {
        if (timestampNanos < 0) throw new IllegalArgumentException("negative source timestamp");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(reason, "reason");
        if (available) {
            positive("meters", meters);
            if (timestampNanos <= 0 || reason != PhysicalReason.AVAILABLE
                    || quality == EvidenceQuality.UNAVAILABLE || method == DistanceMethod.NOT_AVAILABLE) {
                throw new IllegalArgumentException("available distance needs source time/method/quality");
            }
            if (boundsAvailable != (method == DistanceMethod.OBJECT_SIZE)) {
                throw new IllegalArgumentException("only size-prior ranging has quantified bounds");
            }
            if (boundsAvailable) {
                positive("lowerBoundMeters", lowerBoundMeters);
                positive("upperBoundMeters", upperBoundMeters);
                if (lowerBoundMeters > meters || meters > upperBoundMeters) {
                    throw new IllegalArgumentException("distance must lie within bounds");
                }
            } else if (!Double.isNaN(lowerBoundMeters) || !Double.isNaN(upperBoundMeters)) {
                throw new IllegalArgumentException("unknown bounds must use NaN");
            }
        } else if (!Double.isNaN(meters) || boundsAvailable || !Double.isNaN(lowerBoundMeters)
                || !Double.isNaN(upperBoundMeters) || method != DistanceMethod.NOT_AVAILABLE
                || quality != EvidenceQuality.UNAVAILABLE || reason == PhysicalReason.AVAILABLE) {
            throw new IllegalArgumentException("unavailable distance requires NaNs and an explicit reason");
        }
    }

    private static void positive(String name, double v) {
        Contracts.finite(name, v);
        if (v <= 0d) throw new IllegalArgumentException(name + " must be > 0");
    }

    public static DistanceEstimate of(double meters, DistanceMethod method, EvidenceQuality quality, long ts) {
        return new DistanceEstimate(true, meters, false, Double.NaN, Double.NaN, method,
                quality, ts, PhysicalReason.AVAILABLE);
    }

    public static DistanceEstimate bounded(double meters, double lower, double upper, DistanceMethod method,
                                           EvidenceQuality quality, long ts) {
        return new DistanceEstimate(true, meters, true, lower, upper, method, quality, ts, PhysicalReason.AVAILABLE);
    }

    public static DistanceEstimate unavailable(long ts, PhysicalReason reason) {
        return new DistanceEstimate(false, Double.NaN, false, Double.NaN, Double.NaN,
                DistanceMethod.NOT_AVAILABLE, EvidenceQuality.UNAVAILABLE, ts, reason);
    }
}
