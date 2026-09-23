package kz.zholsafe.physical;

import kz.zholsafe.config.PhysicalEstimationConfig;

import java.util.List;
import java.util.Objects;

/** Ordinary least squares on bounded, same-track METRIC samples at source times, never pixels. */
public final class MetricRangeRateEstimator {
    public RangeRateEstimate fit(List<MetricDistanceSample> samples, PhysicalEstimationConfig config) {
        Objects.requireNonNull(samples, "samples");
        Objects.requireNonNull(config, "config");
        int n = samples.size();
        long ts = n == 0 ? 0 : samples.get(n - 1).timestampNanos();
        if (n == 0) {
            return RangeRateEstimate.unavailable(ts, PhysicalReason.INSUFFICIENT_HISTORY, 0);
        }
        if (n > config.maximumMetricSamples()) {
            return RangeRateEstimate.unavailable(ts, PhysicalReason.OUT_OF_RANGE, n);
        }
        long first = samples.get(0).timestampNanos();
        int id = samples.get(0).trackId();
        double[] t = new double[n];
        double sumTime = 0d, sumDistance = 0d;
        EvidenceQuality quality = EvidenceQuality.HIGH;
        for (int i = 0; i < n; i++) {
            MetricDistanceSample sample = samples.get(i);
            if (sample.trackId() != id) {
                return RangeRateEstimate.unavailable(ts, PhysicalReason.CROSS_TRACK_HISTORY, n);
            }
            if (!sample.quality().atLeast(config.minimumDistanceQualityForRate())) {
                return RangeRateEstimate.unavailable(ts, PhysicalReason.LOW_QUALITY, n);
            }
            if (sample.quality().ordinal() < quality.ordinal()) quality = sample.quality();
            if (i > 0) {
                double gap = ((double) sample.timestampNanos() - samples.get(i - 1).timestampNanos()) / 1e9;
                if (gap <= 0d || gap > config.maximumMetricSampleGapSeconds()) {
                    return RangeRateEstimate.unavailable(ts, PhysicalReason.INVALID_TIMESTAMP, n);
                }
            }
            // Subtract in integer domain to retain nanosecond resolution even for large source clocks.
            try {
                t[i] = Math.subtractExact(sample.timestampNanos(), first) / 1e9;
            } catch (ArithmeticException ex) {
                return RangeRateEstimate.unavailable(ts, PhysicalReason.INVALID_TIMESTAMP, n);
            }
            sumTime += t[i];
            sumDistance += sample.meters();
        }
        if (n < config.minimumMetricSamples() || t[n - 1] < config.minimumMetricTimeSpanSeconds()) {
            return RangeRateEstimate.unavailable(ts, PhysicalReason.INSUFFICIENT_HISTORY, n);
        }
        double meanT = sumTime / n, meanD = sumDistance / n;
        double numerator = 0d, denominator = 0d;
        for (int i = 0; i < n; i++) {
            double dt = t[i] - meanT;
            numerator += dt * (samples.get(i).meters() - meanD);
            denominator += dt * dt;
        }
        if (!Double.isFinite(denominator) || denominator <= 0d) {
            return RangeRateEstimate.unavailable(ts, PhysicalReason.INVALID_TIMESTAMP, n);
        }
        double rate = numerator / denominator;
        double squaredResidual = 0d;
        for (int i = 0; i < n; i++) {
            double residual = samples.get(i).meters() - meanD - rate * (t[i] - meanT);
            squaredResidual += residual * residual;
        }
        double rms = Math.sqrt(squaredResidual / n);
        if (!Double.isFinite(rate) || !Double.isFinite(rms) || rms > config.maximumRangeFitRmsMeters()) {
            return RangeRateEstimate.unavailable(ts, PhysicalReason.LOW_QUALITY, n);
        }
        return RangeRateEstimate.of(rate, rms, n, quality, ts);
    }
}
