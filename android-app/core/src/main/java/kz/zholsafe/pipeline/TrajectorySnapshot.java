package kz.zholsafe.pipeline;

import kz.zholsafe.trajectory.ObjectTrajectory;
import kz.zholsafe.trajectory.TrajectoryStatus;

import java.util.List;
import java.util.Objects;

/** Immutable latest image-space analysis; unavailable never means a successful empty road. */
public record TrajectorySnapshot(long frameTimestampNanos, int uprightWidth, int uprightHeight,
        Status status, TrackingSnapshot.Status upstreamStatus, List<ObjectTrajectory> objects) {
    public enum Status { READY, NOT_STARTED, TRACKING_UNAVAILABLE, ESTIMATOR_ERROR }

    public TrajectorySnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(upstreamStatus, "upstreamStatus");
        objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
        if (status == Status.READY) {
            if (upstreamStatus != TrackingSnapshot.Status.READY || frameTimestampNanos <= 0
                    || uprightWidth <= 0 || uprightHeight <= 0) {
                throw new IllegalArgumentException("READY requires successful tracking and source dimensions/time");
            }
        } else {
            if (!objects.isEmpty()) throw new IllegalArgumentException("unavailable trajectory cannot publish objects");
            if ((status == Status.NOT_STARTED && upstreamStatus != TrackingSnapshot.Status.NOT_STARTED)
                    || (status == Status.TRACKING_UNAVAILABLE && upstreamStatus == TrackingSnapshot.Status.READY)
                    || (status == Status.ESTIMATOR_ERROR && upstreamStatus != TrackingSnapshot.Status.READY)) {
                throw new IllegalArgumentException("trajectory status contradicts upstream tracking status");
            }
        }
    }

    public static TrajectorySnapshot unavailable(long ts, Status status, TrackingSnapshot.Status upstream) {
        if (status == Status.READY) throw new IllegalArgumentException("READY is available");
        return new TrajectorySnapshot(ts, 0, 0, status, upstream, List.of());
    }

    public boolean available() { return status == Status.READY; }

    public long availableCount() {
        return objects.stream().filter(v -> v.status() == TrajectoryStatus.AVAILABLE).count();
    }
}
