package kz.zholsafe.physical;

import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;

import java.util.List;
import java.util.Objects;

/** READY with an empty list = successful empty road; errors have explicit status and no objects. */
public record PhysicalEstimationSnapshot(long frameTimestampNanos, int uprightWidth, int uprightHeight,
        Status status, TrackingSnapshot.Status trackingStatus, TrajectorySnapshot.Status trajectoryStatus,
        List<PhysicalObjectEstimate> objects) {
    public enum Status { READY, NOT_STARTED, TRACKING_UNAVAILABLE, TRAJECTORY_UNAVAILABLE,
        INVALID_TIMESTAMP, ESTIMATOR_ERROR }

    public PhysicalEstimationSnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(trackingStatus, "trackingStatus");
        Objects.requireNonNull(trajectoryStatus, "trajectoryStatus");
        objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
        if (frameTimestampNanos < 0) throw new IllegalArgumentException("negative source timestamp");
        if (status == Status.READY) {
            if (trackingStatus != TrackingSnapshot.Status.READY
                    || trajectoryStatus != TrajectorySnapshot.Status.READY
                    || frameTimestampNanos <= 0 || uprightWidth <= 0 || uprightHeight <= 0) {
                throw new IllegalArgumentException("READY requires valid synchronized upstream snapshots");
            }
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            for (PhysicalObjectEstimate object : objects) {
                if (!ids.add(object.trackId())) throw new IllegalArgumentException("duplicate track id");
                if (object.timestampNanos() != frameTimestampNanos) {
                    throw new IllegalArgumentException("object timestamp != source timestamp");
                }
            }
        } else {
            if (!objects.isEmpty()) {
                throw new IllegalArgumentException("unavailable snapshot cannot publish objects");
            }
            boolean consistent = switch (status) {
                case NOT_STARTED -> trackingStatus == TrackingSnapshot.Status.NOT_STARTED
                        && trajectoryStatus == TrajectorySnapshot.Status.NOT_STARTED;
                case TRACKING_UNAVAILABLE -> trackingStatus != TrackingSnapshot.Status.READY
                        && trackingStatus != TrackingSnapshot.Status.NOT_STARTED
                        && trajectoryStatus == TrajectorySnapshot.Status.TRACKING_UNAVAILABLE;
                case TRAJECTORY_UNAVAILABLE -> trackingStatus == TrackingSnapshot.Status.READY
                        && trajectoryStatus == TrajectorySnapshot.Status.ESTIMATOR_ERROR;
                case INVALID_TIMESTAMP, ESTIMATOR_ERROR -> trackingStatus == TrackingSnapshot.Status.READY
                        && trajectoryStatus == TrajectorySnapshot.Status.READY;
                case READY -> false;
            };
            if (!consistent) throw new IllegalArgumentException("physical status contradicts upstream status");
        }
    }

    public static PhysicalEstimationSnapshot unavailable(long ts, Status status,
            TrackingSnapshot.Status trackingStatus, TrajectorySnapshot.Status trajectoryStatus) {
        if (status == Status.READY) throw new IllegalArgumentException("READY is not unavailable");
        return new PhysicalEstimationSnapshot(ts, 0, 0, status, trackingStatus, trajectoryStatus, List.of());
    }

    public boolean available() { return status == Status.READY; }
}
