package kz.zholsafe.tracking;

import java.util.List;
import java.util.Objects;

/** Immutable per-track output. LOST retains its last observed box; do not draw it as a live detection. */
public record TrackView(TrackedObject object, TrackState state, int hits, int missedFrames,
                        List<TrackObservation> history) {
    public TrackView {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(state, "state");
        if (state == TrackState.REMOVED) throw new IllegalArgumentException("removed tracks are not output");
        if (hits < 1 || missedFrames < 0) throw new IllegalArgumentException("invalid track counters");
        history = List.copyOf(Objects.requireNonNull(history, "history"));
        if (history.isEmpty()) throw new IllegalArgumentException("track needs an observation");
    }
}
