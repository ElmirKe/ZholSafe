package kz.zholsafe.tracking;

import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Contracts;
import kz.zholsafe.model.Estimate;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.model.Point2D;

import java.util.List;
import java.util.Objects;

/**
 * DATA CONTRACT: a detection that has been associated across frames.
 *
 * <p>Distance and TTC are {@link Estimate}s: consumers MUST check {@code available()} and
 * treat values as approximate. Never display them as exact measurements.
 *
 * @param trackId           stable id for the lifetime of the track
 * @param objectClass       majority / latest class of the track
 * @param confidence        latest detection confidence
 * @param box               latest bounding box
 * @param positionHistory   recent centre points, oldest first, bounded by tracker config
 * @param movement          coarse movement classification
 * @param estimatedDistance approximate distance in metres, if estimable
 * @param estimatedTtc      approximate time-to-collision in seconds, if estimable
 * @param inDrivingCorridor whether the latest box intersects the configured driving corridor
 * @param ageFrames         successful detector frames since creation, including misses (Stage 3)
 * @param timestampNanos    timestamp of the latest matched observation; LOST boxes are stale (Stage 3)
 */
public record TrackedObject(
        int trackId,
        ObjectClass objectClass,
        float confidence,
        BoundingBox box,
        List<Point2D> positionHistory,
        MovementClass movement,
        Estimate estimatedDistance,
        Estimate estimatedTtc,
        boolean inDrivingCorridor,
        int ageFrames,
        long timestampNanos) {

    public TrackedObject {
        Objects.requireNonNull(objectClass, "objectClass");
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(estimatedDistance, "estimatedDistance");
        Objects.requireNonNull(estimatedTtc, "estimatedTtc");
        positionHistory = List.copyOf(Objects.requireNonNull(positionHistory, "positionHistory"));
        Contracts.unit("confidence", confidence);
        Contracts.nonNegative("ageFrames", (long) ageFrames);
        if (estimatedDistance.available() && estimatedDistance.value() < 0d) {
            throw new IllegalArgumentException("estimatedDistance must be >= 0 when available");
        }
        if (estimatedTtc.available() && estimatedTtc.value() < 0d) {
            throw new IllegalArgumentException("estimatedTtc must be >= 0 when available (negative TTC => unavailable)");
        }
    }

    public Point2D center() {
        return box.center();
    }

    /** Explicit flag mirroring the contract wording: is the distance an available estimate? */
    public boolean distanceEstimated() {
        return estimatedDistance.available();
    }

    public boolean ttcEstimated() {
        return estimatedTtc.available();
    }
}
