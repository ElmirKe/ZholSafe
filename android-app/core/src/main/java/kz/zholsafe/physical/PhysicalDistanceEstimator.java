package kz.zholsafe.physical;

import kz.zholsafe.config.PhysicalEstimationConfig;
import kz.zholsafe.tracking.TrackedObject;

/** Separate availability-rich Stage 4.1 port; legacy scalar Estimate ports are not reinterpreted. */
public interface PhysicalDistanceEstimator {
    DistanceEstimate estimate(TrackedObject track, int width, int height,
                              CameraCalibration calibration, PhysicalEstimationConfig config);
}
