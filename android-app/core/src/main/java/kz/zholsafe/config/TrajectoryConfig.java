package kz.zholsafe.config;

import kz.zholsafe.model.Contracts;

/**
 * Stage 4.0 image-only regression parameters. EXPERIMENTAL engineering defaults, not validated
 * safety thresholds. Samples come from bounded Stage 3 matched observations (no images retained).
 *
 * @param minSamples minimum observations for a fit (at least 3; no single-frame inference)
 * @param maxSamples recent observations considered; may exceed tracker history but cannot create data
 * @param minimumTimeSpanSeconds minimum first-to-last source timestamp span for a useful fit
 * @param stationaryVelocityThreshold normalized center speed (frame fractions / second)
 * @param diagonalComponentRatio minor/major normalized velocity ratio for diagonal direction
 * @param scaleStableThreshold absolute log(normalized area) rate / second considered stable
 * @param approachGrowthThreshold positive log-area rate / second for qualitative approach
 * @param recedeShrinkThreshold positive magnitude of negative log-area rate / second for receding
 * @param maxCenterRmsFraction maximum normalized center fit residual (frame fractions)
 * @param maxLogAreaRms maximum dimensionless log-area fit residual
 */
public record TrajectoryConfig(int minSamples, int maxSamples, double minimumTimeSpanSeconds,
        double stationaryVelocityThreshold, double diagonalComponentRatio, double scaleStableThreshold,
        double approachGrowthThreshold, double recedeShrinkThreshold,
        double maxCenterRmsFraction, double maxLogAreaRms) {

    public TrajectoryConfig {
        if (minSamples < 3) throw new IllegalArgumentException("minSamples must be >= 3");
        if (maxSamples < minSamples) throw new IllegalArgumentException("maxSamples must be >= minSamples");
        positive("minimumTimeSpanSeconds", minimumTimeSpanSeconds);
        nonNegative("stationaryVelocityThreshold", stationaryVelocityThreshold);
        Contracts.range("diagonalComponentRatio", diagonalComponentRatio, 0d, 1d);
        if (diagonalComponentRatio == 0d) throw new IllegalArgumentException("diagonalComponentRatio must be > 0");
        nonNegative("scaleStableThreshold", scaleStableThreshold);
        positive("approachGrowthThreshold", approachGrowthThreshold);
        positive("recedeShrinkThreshold", recedeShrinkThreshold);
        if (approachGrowthThreshold <= scaleStableThreshold || recedeShrinkThreshold <= scaleStableThreshold) {
            throw new IllegalArgumentException("approach/recede thresholds must exceed scaleStableThreshold");
        }
        positive("maxCenterRmsFraction", maxCenterRmsFraction);
        positive("maxLogAreaRms", maxLogAreaRms);
    }

    private static void nonNegative(String name, double value) {
        Contracts.finite(name, value);
        if (value < 0d) throw new IllegalArgumentException(name + " must be >= 0");
    }

    private static void positive(String name, double value) {
        Contracts.finite(name, value);
        if (value <= 0d) throw new IllegalArgumentException(name + " must be > 0");
    }

    public static TrajectoryConfig defaults() {
        return new TrajectoryConfig(3, 8, 0.25, 0.012, 0.35, 0.03, 0.12, 0.12, 0.03, 0.12);
    }
}
