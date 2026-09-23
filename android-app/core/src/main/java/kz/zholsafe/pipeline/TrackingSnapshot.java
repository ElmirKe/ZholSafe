package kz.zholsafe.pipeline;

import kz.zholsafe.tracking.TrackState;
import kz.zholsafe.tracking.TrackView;
import kz.zholsafe.tracking.TrackedObject;

import java.util.List;
import java.util.Objects;

/** Latest immutable tracking result; unavailable is NEVER an empty successful road observation. */
public record TrackingSnapshot(long frameTimestampNanos, int uprightWidth, int uprightHeight,
                               Status status, List<TrackView> tracks) {
    public enum Status { READY, NOT_STARTED, DETECTOR_UNAVAILABLE, TRACKER_ERROR }

    public TrackingSnapshot {
        Objects.requireNonNull(status, "status");
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        if (status != Status.READY && !tracks.isEmpty()) {
            throw new IllegalArgumentException("unavailable tracking cannot publish tracks");
        }
        if (status == Status.READY && (frameTimestampNanos <= 0 || uprightWidth <= 0 || uprightHeight <= 0)) {
            throw new IllegalArgumentException("ready tracking requires positive frame timestamp and dimensions");
        }
    }

    public static TrackingSnapshot unavailable(long timestamp, Status status) {
        if (status == Status.READY) throw new IllegalArgumentException("READY is available");
        return new TrackingSnapshot(timestamp, 0, 0, status, List.of());
    }

    public boolean available() { return status == Status.READY; }

    public long confirmedCount() { return count(TrackState.CONFIRMED); }
    public long tentativeCount() { return count(TrackState.TENTATIVE); }
    public long lostCount() { return count(TrackState.LOST); }

    private long count(TrackState state) { return tracks.stream().filter(t -> t.state() == state).count(); }

    /** Only CURRENTLY OBSERVED confirmed objects; never include stale LOST boxes in future risk input. */
    public List<TrackedObject> confirmedObjects() {
        return tracks.stream().filter(t -> t.state() == TrackState.CONFIRMED).map(TrackView::object).toList();
    }
}
