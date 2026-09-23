package kz.zholsafe.physical;

import kz.zholsafe.config.PhysicalEstimationConfig;
import kz.zholsafe.trajectory.ObjectTrajectory;
import kz.zholsafe.trajectory.ScaleChange;
import kz.zholsafe.trajectory.TrajectoryQuality;

import java.util.Objects;

/** Distinct metric and uncalibrated optical TTC algorithms, with no speed sensor or risk output. */
public final class PhysicalTtcEstimator implements PhysicalTtcPort {
    @Override
    public TtcEstimate metric(DistanceEstimate distance, RangeRateEstimate rate,
                              PhysicalEstimationConfig config) {
        Objects.requireNonNull(distance, "distance");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(config, "config");
        long ts = distance.timestampNanos();
        if (!distance.available()) return TtcEstimate.unavailable(ts, distance.reason());
        if (!distance.quality().atLeast(config.minimumDistanceQualityForRate())) {
            return TtcEstimate.unavailable(ts, PhysicalReason.LOW_QUALITY);
        }
        if (!rate.available()) return TtcEstimate.unavailable(ts, rate.reason());
        if (rate.timestampNanos() != ts) return TtcEstimate.unavailable(ts, PhysicalReason.INVALID_TIMESTAMP);
        if (!rate.quality().atLeast(config.minimumRateQualityForTtc())) {
            return TtcEstimate.unavailable(ts, PhysicalReason.LOW_QUALITY);
        }
        if (rate.closingSpeedMps() < config.minimumClosingSpeedMps()) {
            return TtcEstimate.unavailable(ts, PhysicalReason.NOT_CLOSING);
        }
        double ttc = distance.meters() / rate.closingSpeedMps();
        if (!Double.isFinite(ttc) || ttc > config.maximumReportedTtcSeconds()) {
            return TtcEstimate.unavailable(ts, PhysicalReason.OUT_OF_RANGE);
        }
        EvidenceQuality quality = distance.quality().ordinal() < rate.quality().ordinal()
                ? distance.quality() : rate.quality();
        return TtcEstimate.of(ttc, TtcMethod.METRIC_RANGE, quality, ts);
    }

    @Override
    public TtcEstimate imageScale(ObjectTrajectory trajectory, long currentTimestampNanos,
                                  PhysicalEstimationConfig config) {
        Objects.requireNonNull(config, "config");
        if (currentTimestampNanos <= 0) {
            return TtcEstimate.unavailable(Math.max(0, currentTimestampNanos), PhysicalReason.INVALID_TIMESTAMP);
        }
        if (trajectory != null && trajectory.timestampNanos() != currentTimestampNanos) {
            return TtcEstimate.unavailable(currentTimestampNanos, PhysicalReason.INVALID_TIMESTAMP);
        }
        if (trajectory == null || !trajectory.available()
                || trajectory.quality() != TrajectoryQuality.FIT_ACCEPTED
                || trajectory.scale().change() != ScaleChange.GROWING) {
            return TtcEstimate.unavailable(currentTimestampNanos, PhysicalReason.INSUFFICIENT_HISTORY);
        }
        double growth = trajectory.scale().logAreaRatePerSecond();
        if (!Double.isFinite(growth) || growth < config.minimumScaleGrowthRatePerSecond()
                || trajectory.logAreaFitRms() > config.maximumScaleFitRms()) {
            return TtcEstimate.unavailable(currentTimestampNanos, PhysicalReason.LOW_QUALITY);
        }
        double ttc = 2d / growth;
        if (!Double.isFinite(ttc) || ttc <= 0d || ttc > config.maximumReportedTtcSeconds()) {
            return TtcEstimate.unavailable(currentTimestampNanos, PhysicalReason.OUT_OF_RANGE);
        }
        // Optical expansion cannot be promoted to metric or even medium-quality physical evidence.
        return TtcEstimate.of(ttc, TtcMethod.IMAGE_SCALE, EvidenceQuality.LOW, currentTimestampNanos);
    }

    /** No averaging or method erasure. Conflict invalidates the combined diagnostic. */
    @Override
    public TtcEstimate selected(TtcEstimate metric, TtcEstimate optical, PhysicalEstimationConfig config) {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(optical, "optical");
        Objects.requireNonNull(config, "config");
        if (metric.available() && optical.available()) {
            if (metric.timestampNanos() != optical.timestampNanos()) {
                return TtcEstimate.unavailable(metric.timestampNanos(), PhysicalReason.INVALID_TIMESTAMP);
            }
            double discrepancy = Math.abs(metric.seconds() - optical.seconds())
                    / Math.max(metric.seconds(), optical.seconds());
            if (discrepancy > config.ttcAgreementRelativeTolerance()) {
                return TtcEstimate.unavailable(metric.timestampNanos(), PhysicalReason.CONFLICTING_ESTIMATES);
            }
        }
        return metric.available() ? metric : optical.available() ? optical : metric;
    }
}
