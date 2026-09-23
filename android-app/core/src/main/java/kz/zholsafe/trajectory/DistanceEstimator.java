package kz.zholsafe.trajectory;

import kz.zholsafe.model.Estimate;
import kz.zholsafe.tracking.TrackedObject;

/**
 * Pluggable distance estimator.
 *
 * <p>A future Stage 4.1 estimator, IF justified by validation, may use an uncalibrated monocular
 * heuristic and must then return {@link kz.zholsafe.model.EstimationMethod#MONOCULAR_UNCALIBRATED}.
 * It can later be
 * replaced by calibrated monocular, stereo, radar, LiDAR or vehicle-sensor implementations
 * without changing the Risk Engine. Return {@link Estimate#unavailable()} when the inputs do not
 * justify an estimate (e.g. unknown class, truncated box). Produce values with
 * {@link Estimate#distance(double, kz.zholsafe.model.EstimationMethod)}: distance is {@code >= 0}.
 */
public interface DistanceEstimator {

    Estimate estimateDistanceMeters(TrackedObject object, int frameWidth, int frameHeight);

    /** Always-unavailable implementation, used until a real estimator exists and in tests. */
    DistanceEstimator UNAVAILABLE = (object, w, h) -> Estimate.unavailable();
}
