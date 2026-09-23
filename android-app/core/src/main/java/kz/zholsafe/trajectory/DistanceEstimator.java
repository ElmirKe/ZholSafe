package kz.zholsafe.trajectory;

import kz.zholsafe.model.Estimate;
import kz.zholsafe.tracking.TrackedObject;

/**
 * Pluggable distance estimator.
 *
 * <p>Legacy Stage 0 scalar/Risk Engine port, deliberately UNAVAILABLE in Stage 4.1. It lacks
 * calibration, source-time, uncertainty and per-track evidence. The separate Stage 4.1
 * {@link kz.zholsafe.physical.PhysicalDistanceEstimator} returns rich, method-labelled physical
 * diagnostics without modifying TrackedObject or RiskInput. Do not silently convert its values
 * into this legacy port. Future integration requires independent validation and Stage 4.2 review.
 */
public interface DistanceEstimator {

    Estimate estimateDistanceMeters(TrackedObject object, int frameWidth, int frameHeight);

    /** Always-unavailable legacy implementation (including Stage 4.1) and test default. */
    DistanceEstimator UNAVAILABLE = (object, w, h) -> Estimate.unavailable();
}
