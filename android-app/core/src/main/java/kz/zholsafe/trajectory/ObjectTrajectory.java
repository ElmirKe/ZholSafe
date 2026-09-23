package kz.zholsafe.trajectory;

import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Contracts;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.model.Point2D;

import java.util.Objects;

/**
 * Immutable image-only analysis of ONE Stage 3 track. timestampNanos and box refer to the last
 * matched source observation; a LOST/non-current track has TRACK_NOT_CURRENT, never an available
 * motion. sampleCount is the number of RECENT observations actually examined (bounded by config).
 * Fit residual units: centre RMS in normalized-frame fractions, log-area RMS dimensionless.
 * These diagnostics are NaN unless status==AVAILABLE. No physical Estimate is produced.
 */
public record ObjectTrajectory(int trackId, ObjectClass objectClass, long timestampNanos,
        BoundingBox box, int sampleCount, TrajectoryStatus status, TrajectoryQuality quality,
        ImageMotion motion, ImageScaleTrend scale, ApproachState approach,
        double timeSpanSeconds, double centerFitRmsFraction, double logAreaFitRms) {

    public ObjectTrajectory {
        if (trackId <= 0) throw new IllegalArgumentException("trackId must be positive");
        Objects.requireNonNull(objectClass, "objectClass");
        Objects.requireNonNull(box, "box");
        if (sampleCount < 0) throw new IllegalArgumentException("sampleCount must be >= 0");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(scale, "scale");
        Objects.requireNonNull(approach, "approach");
        if (status == TrajectoryStatus.AVAILABLE) {
            if (timestampNanos <= 0 || quality != TrajectoryQuality.FIT_ACCEPTED
                    || !motion.available() || !scale.available()) {
                throw new IllegalArgumentException("available trajectory requires accepted image-only fit");
            }
            nonNegative("timeSpanSeconds", timeSpanSeconds);
            nonNegative("centerFitRmsFraction", centerFitRmsFraction);
            nonNegative("logAreaFitRms", logAreaFitRms);
        } else if (quality != TrajectoryQuality.UNAVAILABLE || motion.available() || scale.available()
                || approach != ApproachState.UNCERTAIN || !Double.isNaN(timeSpanSeconds)
                || !Double.isNaN(centerFitRmsFraction) || !Double.isNaN(logAreaFitRms)) {
            throw new IllegalArgumentException("unavailable trajectory must not publish numeric motion/scale");
        }
    }

    private static void nonNegative(String name, double v) {
        Contracts.finite(name, v);
        if (v < 0d) throw new IllegalArgumentException(name + " must be >= 0");
    }

    public static ObjectTrajectory unavailable(int id, ObjectClass cls, long ts, BoundingBox box,
                                                int samples, TrajectoryStatus reason) {
        if (reason == TrajectoryStatus.AVAILABLE) throw new IllegalArgumentException("AVAILABLE is not unavailable");
        return new ObjectTrajectory(id, cls, ts, box, samples, reason, TrajectoryQuality.UNAVAILABLE,
                ImageMotion.unavailable(), ImageScaleTrend.unavailable(), ApproachState.UNCERTAIN,
                Double.NaN, Double.NaN, Double.NaN);
    }

    /** Upright source pixel centre of the last observed box; not a physical position. */
    public Point2D centerPixels() { return box.center(); }

    public boolean available() { return status == TrajectoryStatus.AVAILABLE; }
}
