package kz.zholsafe.tracking;

import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Contracts;
import kz.zholsafe.model.Point2D;

import java.util.Objects;

/** Lightweight immutable matched observation; source timestamp, upright pixel box and score only. */
public record TrackObservation(long timestampNanos, BoundingBox box, float confidence) {
    public TrackObservation {
        if (timestampNanos <= 0) throw new IllegalArgumentException("source timestamp must be positive");
        Objects.requireNonNull(box, "box");
        Contracts.unit("confidence", confidence);
    }

    public Point2D center() { return box.center(); }
}
