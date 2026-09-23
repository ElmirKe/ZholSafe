package kz.zholsafe.physical;

import kz.zholsafe.model.Contracts;

import java.util.Objects;

/** Signed camera-relative optical-depth rate (m/s), NOT independent animal speed. Negative rate
 * is closing; closingSpeedMps=max(0,-rangeRateMps) only for an AVAILABLE fit. */
public record RangeRateEstimate(boolean available, double rangeRateMps, double closingSpeedMps,
        double fitRmsMeters, int sampleCount, RangeRateMethod method, EvidenceQuality quality,
        long timestampNanos, PhysicalReason reason) {
    public RangeRateEstimate {
        if (timestampNanos < 0) throw new IllegalArgumentException("negative source timestamp");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(reason, "reason");
        if (sampleCount < 0) throw new IllegalArgumentException("sampleCount must be >= 0");
        if (available) {
            Contracts.finite("rangeRateMps", rangeRateMps);
            Contracts.finite("closingSpeedMps", closingSpeedMps);
            Contracts.finite("fitRmsMeters", fitRmsMeters);
            if (closingSpeedMps < 0 || fitRmsMeters < 0
                    || Double.compare(closingSpeedMps, Math.max(0d, -rangeRateMps)) != 0
                    || sampleCount < 3 || timestampNanos <= 0 || method != RangeRateMethod.METRIC_REGRESSION
                    || quality == EvidenceQuality.UNAVAILABLE || reason != PhysicalReason.AVAILABLE) {
                throw new IllegalArgumentException("invalid available range rate");
            }
        } else if (!Double.isNaN(rangeRateMps) || !Double.isNaN(closingSpeedMps)
                || !Double.isNaN(fitRmsMeters) || method != RangeRateMethod.NOT_AVAILABLE
                || quality != EvidenceQuality.UNAVAILABLE || reason == PhysicalReason.AVAILABLE) {
            throw new IllegalArgumentException("unavailable rate must use NaN/reason");
        }
    }

    public static RangeRateEstimate of(double rate, double rms, int count, EvidenceQuality quality, long ts) {
        return new RangeRateEstimate(true, rate, Math.max(0d, -rate), rms, count,
                RangeRateMethod.METRIC_REGRESSION, quality, ts, PhysicalReason.AVAILABLE);
    }

    public static RangeRateEstimate unavailable(long ts, PhysicalReason reason, int count) {
        return new RangeRateEstimate(false, Double.NaN, Double.NaN, Double.NaN, count,
                RangeRateMethod.NOT_AVAILABLE, EvidenceQuality.UNAVAILABLE, ts, reason);
    }
}
