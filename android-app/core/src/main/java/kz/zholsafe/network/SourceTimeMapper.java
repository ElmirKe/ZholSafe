package kz.zholsafe.network;

import java.time.Instant;
import java.util.Objects;

/** Explicit monotonic-source-time to wall-clock conversion using a captured clock anchor. */
public record SourceTimeMapper(long anchorElapsedNanos, Instant anchorWallClock) {
    public SourceTimeMapper {
        if (anchorElapsedNanos < 0) throw new IllegalArgumentException("negative anchor");
        Objects.requireNonNull(anchorWallClock, "anchorWallClock");
    }
    public Instant toWallClock(long sourceElapsedNanos) {
        return anchorWallClock.plusNanos(Math.subtractExact(sourceElapsedNanos, anchorElapsedNanos));
    }
}
