package kz.zholsafe.config;

import kz.zholsafe.model.Contracts;

/**
 * Stage 4.3 fusion freshness budgets for {@code CombinedRiskEngine}.
 *
 * <p>All comparisons use SOURCE TIMESTAMPS of the road and driver snapshots (independent camera
 * clock domains are never mixed with {@code System.nanoTime()}); exact timestamp equality is never
 * required. <b>ALL VALUES ARE EXPERIMENTAL demo values, NOT safety validated.</b>
 *
 * @param maximumRoadAgeSeconds        max age of the road snapshot vs the source-time reference
 *                                     (the later of the two component timestamps)
 * @param maximumDriverAgeSeconds      max age of the driver snapshot vs the source-time reference
 * @param maximumRoadDriverSkewSeconds max |road − driver| source-time skew for real fusion;
 *                                     beyond it the engine degrades to explicit single-source output
 */
public record CombinedRiskConfig(
        double maximumRoadAgeSeconds,
        double maximumDriverAgeSeconds,
        double maximumRoadDriverSkewSeconds) {

    public CombinedRiskConfig {
        Contracts.finite("maximumRoadAgeSeconds", maximumRoadAgeSeconds);
        Contracts.finite("maximumDriverAgeSeconds", maximumDriverAgeSeconds);
        Contracts.finite("maximumRoadDriverSkewSeconds", maximumRoadDriverSkewSeconds);
        if (maximumRoadAgeSeconds <= 0d || maximumDriverAgeSeconds <= 0d
                || maximumRoadDriverSkewSeconds <= 0d) {
            throw new IllegalArgumentException("freshness budgets must be > 0");
        }
    }

    /** EXPERIMENTAL demo defaults — NOT safety validated. */
    public static CombinedRiskConfig defaults() {
        return new CombinedRiskConfig(2.0d, 2.0d, 1.0d);
    }
}
