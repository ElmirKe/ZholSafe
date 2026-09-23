package kz.zholsafe.risk;

import kz.zholsafe.physical.PhysicalEstimationSnapshot;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.Set;

/** No active hazards are published on upstream failure; READY empty is an observed empty road. */
public record RoadRiskSnapshot(long frameTimestampNanos, int uprightWidth, int uprightHeight,
        Status status, TrackingSnapshot.Status trackingStatus, TrajectorySnapshot.Status trajectoryStatus,
        PhysicalEstimationSnapshot.Status physicalStatus, List<ObjectRiskAssessment> objects,
        Optional<RiskLevel> highestLevel, OptionalInt highestRiskTrackId) {
    public enum Status { READY, NOT_STARTED, TRACKING_UNAVAILABLE, TRAJECTORY_UNAVAILABLE,
        PHYSICAL_UNAVAILABLE, INVALID_TIMESTAMP, ENGINE_ERROR }

    public RoadRiskSnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(trackingStatus, "trackingStatus");
        Objects.requireNonNull(trajectoryStatus, "trajectoryStatus");
        Objects.requireNonNull(physicalStatus, "physicalStatus");
        Objects.requireNonNull(highestLevel, "highestLevel");
        Objects.requireNonNull(highestRiskTrackId, "highestRiskTrackId");
        objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
        if (frameTimestampNanos < 0) throw new IllegalArgumentException("negative source time");
        if (status == Status.READY) {
            if (frameTimestampNanos <= 0 || uprightWidth <= 0 || uprightHeight <= 0
                    || trackingStatus != TrackingSnapshot.Status.READY
                    || trajectoryStatus != TrajectorySnapshot.Status.READY
                    || physicalStatus != PhysicalEstimationSnapshot.Status.READY) {
                throw new IllegalArgumentException("READY requires synchronized operational upstream");
            }
            RiskLevel max = RiskLevel.NORMAL;
            int id = 0;
            double maxScore = -1d;
            Set<Integer> seen = new HashSet<>();
            for (ObjectRiskAssessment o : objects) {
                if (o.timestampNanos() != frameTimestampNanos || !seen.add(o.trackId())) {
                    throw new IllegalArgumentException("duplicate/stale object risk");
                }
                if (o.level().ordinal() > max.ordinal()
                        || (o.level() == max && o.level() != RiskLevel.NORMAL
                        && (o.engineeringScore() > maxScore
                        || (o.engineeringScore() == maxScore && o.trackId() < id)))) {
                    max = o.level();
                    id = o.trackId();
                    maxScore = o.engineeringScore();
                }
            }
            if (highestLevel.isEmpty() || max != highestLevel.get()
                    || (max == RiskLevel.NORMAL && highestRiskTrackId.isPresent())
                    || (max != RiskLevel.NORMAL && (highestRiskTrackId.isEmpty()
                    || highestRiskTrackId.getAsInt() != id))) {
                throw new IllegalArgumentException("global risk must equal max; select highest score then lowest ID");
            }
        } else {
            if (!objects.isEmpty() || highestLevel.isPresent() || highestRiskTrackId.isPresent()
                    || uprightWidth != 0 || uprightHeight != 0) {
                throw new IllegalArgumentException("unavailable risk cannot publish apparently safe frame");
            }
            boolean consistent = switch (status) {
                case NOT_STARTED -> trackingStatus == TrackingSnapshot.Status.NOT_STARTED
                        && trajectoryStatus == TrajectorySnapshot.Status.NOT_STARTED
                        && physicalStatus == PhysicalEstimationSnapshot.Status.NOT_STARTED;
                case TRACKING_UNAVAILABLE -> trackingStatus != TrackingSnapshot.Status.READY
                        && trackingStatus != TrackingSnapshot.Status.NOT_STARTED;
                case TRAJECTORY_UNAVAILABLE -> trackingStatus == TrackingSnapshot.Status.READY
                        && trajectoryStatus != TrajectorySnapshot.Status.READY;
                case PHYSICAL_UNAVAILABLE -> trackingStatus == TrackingSnapshot.Status.READY
                        && trajectoryStatus == TrajectorySnapshot.Status.READY
                        && physicalStatus != PhysicalEstimationSnapshot.Status.READY;
                case INVALID_TIMESTAMP, ENGINE_ERROR -> trackingStatus == TrackingSnapshot.Status.READY
                        && trajectoryStatus == TrajectorySnapshot.Status.READY
                        && physicalStatus == PhysicalEstimationSnapshot.Status.READY;
                case READY -> false;
            };
            if (!consistent) throw new IllegalArgumentException("risk status contradicts upstream status");
        }
    }

    public static RoadRiskSnapshot unavailable(long ts, Status status,
            TrackingSnapshot.Status tracking, TrajectorySnapshot.Status trajectory,
            PhysicalEstimationSnapshot.Status physical) {
        if (status == Status.READY) throw new IllegalArgumentException("READY is available");
        return new RoadRiskSnapshot(ts, 0, 0, status, tracking, trajectory, physical,
                List.of(), Optional.empty(), OptionalInt.empty());
    }

    public boolean available() { return status == Status.READY; }
}
