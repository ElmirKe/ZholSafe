package kz.zholsafe.physical;

import kz.zholsafe.config.PhysicalEstimationConfig;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.tracking.TrackedObject;

import java.util.Objects;

/** Pixel bbox bottom-centre -> camera ray -> flat road-plane intersection. No animal ground
 * contact/road slope/pose correction. Height, pitch and intrinsics MUST be explicitly supplied. */
public final class GroundPlaneDistanceEstimator implements PhysicalDistanceEstimator {
    @Override
    public DistanceEstimate estimate(TrackedObject track, int width, int height,
                                     CameraCalibration calibration, PhysicalEstimationConfig config) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(config, "config");
        long ts = track.timestampNanos();
        if (ts <= 0) return DistanceEstimate.unavailable(Math.max(0, ts), PhysicalReason.INVALID_TIMESTAMP);
        if (calibration == null) return DistanceEstimate.unavailable(ts, PhysicalReason.NO_CALIBRATION);
        if (width != calibration.imageWidth() || height != calibration.imageHeight()) {
            return DistanceEstimate.unavailable(ts, PhysicalReason.CALIBRATION_MISMATCH);
        }
        BoundingBox box = track.box();
        if (!BoxGeometry.fullVisible(box, width, height, config.minimumBoxHeightPixels())) {
            return DistanceEstimate.unavailable(ts, PhysicalReason.INVALID_GEOMETRY);
        }
        double u = ((double) box.x1() + box.x2()) / 2d;
        return GroundPlaneGeometry.intersect(calibration, calibration.ray(u, box.y2()),
                        config.horizonRayMargin())
                .map(p -> DistanceEstimate.of(p.opticalDepthMeters(), DistanceMethod.GROUND_PLANE,
                        calibration.source() == CalibrationSource.MEASURED_INTRINSICS
                                ? EvidenceQuality.MEDIUM : EvidenceQuality.LOW, ts))
                .orElseGet(() -> DistanceEstimate.unavailable(ts, PhysicalReason.INVALID_GEOMETRY));
    }
}
