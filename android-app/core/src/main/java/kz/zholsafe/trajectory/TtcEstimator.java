package kz.zholsafe.trajectory;

import kz.zholsafe.model.Estimate;
import kz.zholsafe.tracking.TrackedObject;
import kz.zholsafe.risk.VehicleContext;

/**
 * Pluggable time-to-collision estimator.
 *
 * <p>Legacy Stage 0 scalar/Risk Engine port, deliberately UNAVAILABLE in Stage 4.1. The
 * separate {@link kz.zholsafe.physical.PhysicalTtcPort} preserves distinct metric-range and
 * uncalibrated image-expansion methods, source time and evidence quality. Do not silently feed
 * those experimental diagnostics into TrackedObject/RiskInput. No negative/non-closing TTC.
 */
public interface TtcEstimator {

    Estimate estimateTtcSeconds(TrackedObject object, VehicleContext vehicle);

    /** Always-unavailable legacy implementation (including Stage 4.1) and test default. */
    TtcEstimator UNAVAILABLE = (object, vehicle) -> Estimate.unavailable();
}
