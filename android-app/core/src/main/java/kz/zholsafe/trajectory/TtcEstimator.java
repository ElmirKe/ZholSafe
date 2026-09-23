package kz.zholsafe.trajectory;

import kz.zholsafe.model.Estimate;
import kz.zholsafe.tracking.TrackedObject;
import kz.zholsafe.risk.VehicleContext;

/**
 * Pluggable time-to-collision estimator.
 *
 * <p>Must return {@link Estimate#unavailable()} unless there is enough information (distance
 * estimate + closing speed, or a reliable scale-change series). Never fabricate a value.
 */
public interface TtcEstimator {

    Estimate estimateTtcSeconds(TrackedObject object, VehicleContext vehicle);

    /** Always-unavailable implementation, used until a real estimator exists and in tests. */
    TtcEstimator UNAVAILABLE = (object, vehicle) -> Estimate.unavailable();
}
