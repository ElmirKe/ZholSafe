package kz.zholsafe.config;

import kz.zholsafe.model.Contracts;

/**
 * Tracker and trajectory parameters (Stage 4 consumers).
 *
 * @param iouMatchThreshold       minimum IoU to associate a detection with an existing track
 * @param maxCoastFrames          frames a track survives without a matching detection
 * @param minHitsToConfirm        detections required before a track is reported
 * @param historyLength           bounded centre-point history per track
 * @param corridorLeftFraction    driving corridor left edge as fraction of frame width
 * @param corridorRightFraction   driving corridor right edge as fraction of frame width
 * @param corridorTopFraction     driving corridor top edge as fraction of frame height
 */
public record TrackingConfig(
        float iouMatchThreshold,
        int maxCoastFrames,
        int minHitsToConfirm,
        int historyLength,
        float corridorLeftFraction,
        float corridorRightFraction,
        float corridorTopFraction) {

    public TrackingConfig {
        Contracts.unit("iouMatchThreshold", iouMatchThreshold);
        Contracts.positive("maxCoastFrames", maxCoastFrames);
        Contracts.positive("minHitsToConfirm", minHitsToConfirm);
        Contracts.positive("historyLength", historyLength);
        Contracts.unit("corridorLeftFraction", corridorLeftFraction);
        Contracts.unit("corridorRightFraction", corridorRightFraction);
        Contracts.unit("corridorTopFraction", corridorTopFraction);
        if (corridorLeftFraction >= corridorRightFraction) {
            throw new IllegalArgumentException("corridorLeftFraction must be < corridorRightFraction");
        }
    }

    public static TrackingConfig defaults() {
        return new TrackingConfig(0.30f, 10, 2, 30, 0.30f, 0.70f, 0.40f);
    }
}
