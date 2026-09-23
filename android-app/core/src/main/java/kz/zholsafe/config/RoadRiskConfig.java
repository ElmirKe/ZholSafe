package kz.zholsafe.config;

import kz.zholsafe.model.Contracts;
import kz.zholsafe.physical.EvidenceQuality;
import kz.zholsafe.risk.NormalizedDrivingCorridor;
import kz.zholsafe.risk.RiskLevel;

import java.util.Objects;

/** Stage 4.2 engineering parameters, NOT calibrated safety thresholds or probability. */
public record RoadRiskConfig(NormalizedDrivingCorridor corridor, ScoreBands scoreBands,
        TtcBands metricTtc, TtcBands opticalTtc, Contributions contributions,
        double nearMarginFraction, double centralHalfWidthFraction,
        double predictionHorizonSeconds, double minimumHorizontalSpeedFractionPerSecond,
        double largeBoxAreaFraction, double minimumDetectionConfidence,
        double minimumClosingMps, double rapidClosingMps,
        double metricMediumQualityFactor, double sizePriorQualityFactor,
        EvidenceQuality minimumMetricQuality) {

    public RoadRiskConfig {
        Objects.requireNonNull(corridor, "corridor");
        Objects.requireNonNull(scoreBands, "scoreBands");
        Objects.requireNonNull(metricTtc, "metricTtc");
        Objects.requireNonNull(opticalTtc, "opticalTtc");
        Objects.requireNonNull(contributions, "contributions");
        Contracts.range("nearMarginFraction", nearMarginFraction, 0d, 1d);
        Contracts.range("centralHalfWidthFraction", centralHalfWidthFraction, 0d, 1d);
        if (centralHalfWidthFraction <= 0d || nearMarginFraction <= 0d) {
            throw new IllegalArgumentException("corridor fractions must be positive");
        }
        positive("predictionHorizonSeconds", predictionHorizonSeconds);
        positive("minimumHorizontalSpeedFractionPerSecond", minimumHorizontalSpeedFractionPerSecond);
        Contracts.range("largeBoxAreaFraction", largeBoxAreaFraction, 0d, 1d);
        if (largeBoxAreaFraction <= 0d) throw new IllegalArgumentException("large box threshold must be positive");
        Contracts.range("minimumDetectionConfidence", minimumDetectionConfidence, 0d, 1d);
        positive("minimumClosingMps", minimumClosingMps);
        positive("rapidClosingMps", rapidClosingMps);
        if (rapidClosingMps <= minimumClosingMps) throw new IllegalArgumentException("rapid > minimum closing");
        Contracts.range("metricMediumQualityFactor", metricMediumQualityFactor, 0d, 1d);
        if (metricMediumQualityFactor <= 0d) throw new IllegalArgumentException("quality factor must be positive");
        Contracts.range("sizePriorQualityFactor", sizePriorQualityFactor, 0d, 1d);
        if (sizePriorQualityFactor <= 0d) throw new IllegalArgumentException("size prior factor must be positive");
        Objects.requireNonNull(minimumMetricQuality, "minimumMetricQuality");
        if (minimumMetricQuality.ordinal() < EvidenceQuality.MEDIUM.ordinal()) {
            throw new IllegalArgumentException("metric risk cannot consume LOW/UNAVAILABLE quality");
        }
    }

    private static void positive(String name, double v) {
        Contracts.finite(name, v);
        if (v <= 0d) throw new IllegalArgumentException(name + " must be > 0");
    }

    public record ScoreBands(double caution, double warning, double critical) {
        public ScoreBands {
            Contracts.range("caution", caution, 0d, 1d);
            Contracts.range("warning", warning, 0d, 1d);
            Contracts.range("critical", critical, 0d, 1d);
            if (!(0d < caution && caution < warning && warning < critical)) {
                throw new IllegalArgumentException("require 0 < caution < warning < critical <= 1");
            }
        }
        public RiskLevel levelFor(double score) {
            Contracts.range("engineeringScore (NOT probability)", score, 0d, 1d);
            return score >= critical ? RiskLevel.CRITICAL : score >= warning ? RiskLevel.WARNING
                    : score >= caution ? RiskLevel.CAUTION : RiskLevel.NORMAL;
        }
    }

    public record TtcBands(double criticalSeconds, double warningSeconds, double cautionSeconds) {
        public TtcBands {
            positive("criticalSeconds", criticalSeconds);
            positive("warningSeconds", warningSeconds);
            positive("cautionSeconds", cautionSeconds);
            if (!(criticalSeconds < warningSeconds && warningSeconds < cautionSeconds)) {
                throw new IllegalArgumentException("TTC seconds require critical < warning < caution");
            }
        }
    }

    /** Named, capped score contributions; every number is an experimental maximum, NOT a probability. */
    public record Contributions(double central, double intersecting, double near,
            double apparentGrowth, double movingToward, double predictedEntry, double trajectoryCap,
            double closing, double rapidClosing, double metricCritical, double metricWarning,
            double metricCaution, double opticalCritical, double opticalWarning, double opticalCaution,
            double largeClass, double largeAppearance) {
        public Contributions {
            for (double v : new double[]{central, intersecting, near, apparentGrowth, movingToward,
                    predictedEntry, trajectoryCap, closing, rapidClosing, metricCritical, metricWarning,
                    metricCaution, opticalCritical, opticalWarning, opticalCaution, largeClass,
                    largeAppearance}) Contracts.range("contribution", v, 0d, 1d);
            if (!(near < intersecting && intersecting <= central && closing <= rapidClosing
                    && metricCaution <= metricWarning && metricWarning <= metricCritical
                    && opticalCaution <= opticalWarning && opticalWarning <= opticalCritical)
                    || apparentGrowth + movingToward + predictedEntry < trajectoryCap) {
                throw new IllegalArgumentException("contribution ordering/cap invalid");
            }
        }
    }

    public static RoadRiskConfig defaults() {
        return new RoadRiskConfig(new NormalizedDrivingCorridor(.5, .30, .12, .40),
                new ScoreBands(.18, .43, .72), new TtcBands(2.5, 5, 9),
                new TtcBands(2.5, 4.5, 8),
                new Contributions(.26, .23, .15, .20, .12, .16, .32, .18, .22,
                        .54, .38, .19, .30, .22, .12, .04, .06),
                .06, .40, 1d, .025, .16, .35, .5, 3d, .80, .60,
                EvidenceQuality.MEDIUM);
    }
}
