package kz.zholsafe.physical;

import kz.zholsafe.config.PhysicalEstimationConfig;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.tracking.TrackedObject;

import java.util.Map;
import java.util.Objects;

/** Approximate pinhole object-height ranging, explicitly opt-in; UNKNOWN is NEVER ranged. */
public final class ObjectSizeDistanceEstimator implements PhysicalDistanceEstimator {
    private final Map<ObjectClass, ObjectSizePrior> priors;

    public ObjectSizeDistanceEstimator(Map<ObjectClass, ObjectSizePrior> priors) {
        this.priors = Map.copyOf(Objects.requireNonNull(priors, "priors"));
        for (var e : this.priors.entrySet()) {
            if (e.getKey() != e.getValue().objectClass()) {
                throw new IllegalArgumentException("prior key/class mismatch");
            }
        }
    }

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
        if (track.objectClass() == ObjectClass.UNKNOWN) {
            return DistanceEstimate.unavailable(ts, PhysicalReason.UNKNOWN_CLASS);
        }
        ObjectSizePrior prior = priors.get(track.objectClass());
        if (prior == null) return DistanceEstimate.unavailable(ts, PhysicalReason.NO_SIZE_PRIOR);
        if (!BoxGeometry.fullVisible(track.box(), width, height, config.minimumBoxHeightPixels())) {
            return DistanceEstimate.unavailable(ts, PhysicalReason.INVALID_GEOMETRY);
        }
        double hPixels = track.box().height();
        double factor = calibration.fyPixels() / hPixels;
        double nominal = factor * prior.nominalMeters();
        double lower = factor * prior.minimumMeters();
        double upper = factor * prior.maximumMeters();
        if (!Double.isFinite(lower) || !Double.isFinite(upper) || !Double.isFinite(nominal)) {
            return DistanceEstimate.unavailable(ts, PhysicalReason.INVALID_GEOMETRY);
        }
        double relativeWidth = (prior.maximumMeters() - prior.minimumMeters()) / prior.nominalMeters();
        EvidenceQuality quality = prior.source() == PriorSource.MEASURED_FOR_OBJECT
                && calibration.source() == CalibrationSource.MEASURED_INTRINSICS
                && relativeWidth <= config.maxPriorRelativeWidthForMedium()
                ? EvidenceQuality.MEDIUM : EvidenceQuality.LOW;
        return DistanceEstimate.bounded(nominal, lower, upper, DistanceMethod.OBJECT_SIZE, quality, ts);
    }
}
