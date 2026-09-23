package kz.zholsafe.physical;

import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.tracking.TrackState;

import java.util.Objects;

/** One Stage 3 track on one frame: independent metric and optical TTC; selected is diagnostic only. */
public record PhysicalObjectEstimate(int trackId, ObjectClass objectClass, TrackState trackState,
        long timestampNanos, DistanceEstimate distance, RangeRateEstimate rangeRate,
        TtcEstimate metricTtc, TtcEstimate imageScaleTtc, TtcEstimate selectedTtc) {
    public PhysicalObjectEstimate {
        if (trackId <= 0 || timestampNanos <= 0) throw new IllegalArgumentException("invalid track/time");
        Objects.requireNonNull(objectClass, "objectClass");
        Objects.requireNonNull(trackState, "trackState");
        Objects.requireNonNull(distance, "distance");
        Objects.requireNonNull(rangeRate, "rangeRate");
        Objects.requireNonNull(metricTtc, "metricTtc");
        Objects.requireNonNull(imageScaleTtc, "imageScaleTtc");
        Objects.requireNonNull(selectedTtc, "selectedTtc");
        if (trackState == TrackState.REMOVED) throw new IllegalArgumentException("removed track");
        if (distance.timestampNanos() != timestampNanos || rangeRate.timestampNanos() != timestampNanos
                || metricTtc.timestampNanos() != timestampNanos || imageScaleTtc.timestampNanos() != timestampNanos
                || selectedTtc.timestampNanos() != timestampNanos) {
            throw new IllegalArgumentException("physical estimates must have source frame timestamp");
        }
        if (trackState != TrackState.CONFIRMED && (distance.available() || rangeRate.available()
                || metricTtc.available() || imageScaleTtc.available() || selectedTtc.available())) {
            throw new IllegalArgumentException("stale or tentative tracks have no physical output");
        }
    }
}
