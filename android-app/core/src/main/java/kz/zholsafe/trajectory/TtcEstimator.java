package kz.zholsafe.trajectory;

import kz.zholsafe.model.Estimate;
import kz.zholsafe.tracking.TrackedObject;
import kz.zholsafe.risk.VehicleContext;

/**
 * Pluggable time-to-collision estimator.
 *
 * <p>Must return {@link Estimate#unavailable()} unless there is enough information (distance
 * estimate + closing speed, or a reliable scale-change series). Never fabricate a value.
 * Produce values with {@link Estimate#ttc(double, kz.zholsafe.model.EstimationMethod)}. A
 * mathematically negative TTC (not closing / closest approach already passed) is NOT a valid
 * estimate in ZholSafe — return {@link Estimate#unavailable()} for it.
 */
public interface TtcEstimator {

    Estimate estimateTtcSeconds(TrackedObject object, VehicleContext vehicle);

    /** Always-unavailable implementation, used until a real estimator exists and in tests. */
    TtcEstimator UNAVAILABLE = (object, vehicle) -> Estimate.unavailable();
}
