package kz.zholsafe.physical;

import kz.zholsafe.config.PhysicalEstimationConfig;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.tracking.TrackedObject;

import java.util.Map;
import java.util.Objects;

/** Ground plane primary; size is an explicit plausibility cross-check/fallback. NEVER average. */
public final class ConservativeDistanceEstimator implements PhysicalDistanceEstimator {
    private final GroundPlaneDistanceEstimator ground = new GroundPlaneDistanceEstimator();
    private final ObjectSizeDistanceEstimator size;

    public ConservativeDistanceEstimator(Map<ObjectClass, ObjectSizePrior> priors) {
        size = new ObjectSizeDistanceEstimator(priors);
    }

    @Override
    public DistanceEstimate estimate(TrackedObject track, int width, int height,
                                     CameraCalibration calibration, PhysicalEstimationConfig config) {
        Objects.requireNonNull(config, "config");
        DistanceEstimate primary = ground.estimate(track, width, height, calibration, config);
        DistanceEstimate secondary = size.estimate(track, width, height, calibration, config);
        if (primary.available() && secondary.available()) {
            // Plausibility interval expanded by a configurable relative tolerance for modelling error.
            // No means, weighted averages, or claims that these are independent sensor measurements.
            if (primary.meters() < secondary.lowerBoundMeters() * (1d - config.fusionRelativeTolerance())
                    || primary.meters() > secondary.upperBoundMeters() * (1d + config.fusionRelativeTolerance())) {
                return DistanceEstimate.unavailable(track.timestampNanos(), PhysicalReason.CONFLICTING_ESTIMATES);
            }
            return DistanceEstimate.of(primary.meters(), DistanceMethod.GROUND_PLANE_CROSS_CHECKED,
                    primary.quality(), track.timestampNanos());
        }
        if (primary.available()) return primary;
        if (secondary.available()) return secondary;
        // If the configured prior conflicts with invalid image geometry, preserve the geometry failure.
        if (secondary.reason() == PhysicalReason.INVALID_GEOMETRY) return secondary;
        return primary;
    }
}
