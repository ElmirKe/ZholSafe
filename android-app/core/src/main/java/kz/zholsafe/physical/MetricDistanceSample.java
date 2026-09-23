package kz.zholsafe.physical;

import java.util.Objects;

/** Lightweight immutable metric sample for ONE track; kept only in a bounded per-track deque. */
public record MetricDistanceSample(int trackId, long timestampNanos, double meters,
                                   EvidenceQuality quality, DistanceMethod method) {
    public MetricDistanceSample {
        if (trackId <= 0 || timestampNanos <= 0 || !Double.isFinite(meters) || meters <= 0d) {
            throw new IllegalArgumentException("invalid metric sample");
        }
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(method, "method");
        if (quality == EvidenceQuality.UNAVAILABLE || method == DistanceMethod.NOT_AVAILABLE) {
            throw new IllegalArgumentException("metric sample requires provenance");
        }
    }
}
