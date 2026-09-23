package kz.zholsafe.config;

import kz.zholsafe.model.Contracts;

/**
 * ByteTrack-inspired tracker parameters. Thresholds are engineering defaults, not measured
 * accuracy settings. Corridor fractions are reserved for later trajectory/risk stages.
 *
 * @param iouMatchThreshold minimum IoU for either association pass (strictly positive)
 * @param maxCoastFrames successful detection frames a confirmed track can miss before removal
 * @param minHitsToConfirm consecutive HIGH-confidence hits required for confirmation
 * @param historyLength maximum matched observations (never raw images) per track
 * @param corridorLeftFraction reserved for later stages
 * @param corridorRightFraction reserved for later stages
 * @param corridorTopFraction reserved for later stages
 * @param highConfidenceThreshold minimum score for primary association/new tracks
 * @param lowConfidenceThreshold minimum score for recovery (inclusive); scores below are ignored
 * @param maxActiveTracks hard cap on retained tracks AND detections processed per frame
 */
public record TrackingConfig(
        float iouMatchThreshold,
        int maxCoastFrames,
        int minHitsToConfirm,
        int historyLength,
        float corridorLeftFraction,
        float corridorRightFraction,
        float corridorTopFraction,
        float highConfidenceThreshold,
        float lowConfidenceThreshold,
        int maxActiveTracks) {

    public TrackingConfig {
        Contracts.unit("iouMatchThreshold", iouMatchThreshold);
        if (iouMatchThreshold == 0f) throw new IllegalArgumentException("iouMatchThreshold must be > 0");
        Contracts.positive("maxCoastFrames", maxCoastFrames);
        Contracts.positive("minHitsToConfirm", minHitsToConfirm);
        Contracts.positive("historyLength", historyLength);
        Contracts.unit("corridorLeftFraction", corridorLeftFraction);
        Contracts.unit("corridorRightFraction", corridorRightFraction);
        Contracts.unit("corridorTopFraction", corridorTopFraction);
        if (corridorLeftFraction >= corridorRightFraction) {
            throw new IllegalArgumentException("corridorLeftFraction must be < corridorRightFraction");
        }
        Contracts.unit("highConfidenceThreshold", highConfidenceThreshold);
        Contracts.unit("lowConfidenceThreshold", lowConfidenceThreshold);
        if (highConfidenceThreshold <= lowConfidenceThreshold) {
            throw new IllegalArgumentException("highConfidenceThreshold must exceed lowConfidenceThreshold");
        }
        Contracts.positive("maxActiveTracks", maxActiveTracks);
    }

    /** Compatibility constructor for Stage 0 call sites. */
    public TrackingConfig(float iouMatchThreshold, int maxCoastFrames, int minHitsToConfirm,
                          int historyLength, float corridorLeftFraction, float corridorRightFraction,
                          float corridorTopFraction) {
        this(iouMatchThreshold, maxCoastFrames, minHitsToConfirm, historyLength,
                corridorLeftFraction, corridorRightFraction, corridorTopFraction, 0.50f, 0.35f, 128);
    }

    public static TrackingConfig defaults() {
        return new TrackingConfig(0.30f, 10, 2, 30, 0.30f, 0.70f, 0.40f);
    }
}
