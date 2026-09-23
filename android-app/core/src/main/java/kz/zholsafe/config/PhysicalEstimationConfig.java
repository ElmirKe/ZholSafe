package kz.zholsafe.config;

import kz.zholsafe.model.Contracts;
import kz.zholsafe.physical.EvidenceQuality;

import java.util.Objects;

/** EXPERIMENTAL engineering thresholds, NOT validated safety limits or measured accuracy. */
public record PhysicalEstimationConfig(
        double horizonRayMargin,
        int minimumBoxHeightPixels,
        double fusionRelativeTolerance,
        double maxPriorRelativeWidthForMedium,
        int minimumMetricSamples,
        int maximumMetricSamples,
        double minimumMetricTimeSpanSeconds,
        double maximumRangeFitRmsMeters,
        double maximumMetricSampleGapSeconds,
        double minimumClosingSpeedMps,
        double minimumScaleGrowthRatePerSecond,
        double maximumScaleFitRms,
        double maximumReportedTtcSeconds,
        double ttcAgreementRelativeTolerance,
        int maxTrackedHistories,
        EvidenceQuality minimumDistanceQualityForRate,
        EvidenceQuality minimumRateQualityForTtc) {

    public PhysicalEstimationConfig {
        positive("horizonRayMargin", horizonRayMargin);
        Contracts.positive("minimumBoxHeightPixels", minimumBoxHeightPixels);
        Contracts.range("fusionRelativeTolerance", fusionRelativeTolerance, 0d, 1d);
        Contracts.range("maxPriorRelativeWidthForMedium", maxPriorRelativeWidthForMedium, 0d, 1d);
        if (minimumMetricSamples < 3) throw new IllegalArgumentException("minimumMetricSamples must be >= 3");
        if (maximumMetricSamples < minimumMetricSamples) {
            throw new IllegalArgumentException("maximumMetricSamples must be >= minimumMetricSamples");
        }
        positive("minimumMetricTimeSpanSeconds", minimumMetricTimeSpanSeconds);
        positive("maximumRangeFitRmsMeters", maximumRangeFitRmsMeters);
        positive("maximumMetricSampleGapSeconds", maximumMetricSampleGapSeconds);
        positive("minimumClosingSpeedMps", minimumClosingSpeedMps);
        positive("minimumScaleGrowthRatePerSecond", minimumScaleGrowthRatePerSecond);
        positive("maximumScaleFitRms", maximumScaleFitRms);
        positive("maximumReportedTtcSeconds", maximumReportedTtcSeconds);
        Contracts.range("ttcAgreementRelativeTolerance", ttcAgreementRelativeTolerance, 0d, 1d);
        Contracts.positive("maxTrackedHistories", maxTrackedHistories);
        Objects.requireNonNull(minimumDistanceQualityForRate, "minimumDistanceQualityForRate");
        Objects.requireNonNull(minimumRateQualityForTtc, "minimumRateQualityForTtc");
        if (minimumDistanceQualityForRate == EvidenceQuality.UNAVAILABLE
                || minimumRateQualityForTtc == EvidenceQuality.UNAVAILABLE) {
            throw new IllegalArgumentException("minimum quality must represent evidence");
        }
    }

    private static void positive(String name, double v) {
        Contracts.finite(name, v);
        if (v <= 0d) throw new IllegalArgumentException(name + " must be > 0");
    }

    public static PhysicalEstimationConfig defaults() {
        return new PhysicalEstimationConfig(.03, 12, .35, .50, 3, 8, .5, 2d, 2d,
                .5, .2, .12, 30d, .5, 128, EvidenceQuality.MEDIUM, EvidenceQuality.MEDIUM);
    }
}
