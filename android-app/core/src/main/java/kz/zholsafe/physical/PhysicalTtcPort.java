package kz.zholsafe.physical;

import kz.zholsafe.config.PhysicalEstimationConfig;
import kz.zholsafe.trajectory.ObjectTrajectory;

/** Rich, method-preserving alternative to the legacy scalar trajectory.TtcEstimator port. */
public interface PhysicalTtcPort {
    TtcEstimate metric(DistanceEstimate distance, RangeRateEstimate rate, PhysicalEstimationConfig config);
    TtcEstimate imageScale(ObjectTrajectory trajectory, long currentTimestampNanos,
                           PhysicalEstimationConfig config);
    TtcEstimate selected(TtcEstimate metric, TtcEstimate optical, PhysicalEstimationConfig config);
}
