package kz.zholsafe.physical;

import kz.zholsafe.model.Contracts;

import java.util.Objects;

/** Method-labelled diagnostic seconds. IMAGE_SCALE is optical expansion, never metric range TTC. */
public record TtcEstimate(boolean available, double seconds, TtcMethod method, EvidenceQuality quality,
                          long timestampNanos, PhysicalReason reason) {
    public TtcEstimate {
        if (timestampNanos < 0) throw new IllegalArgumentException("negative source timestamp");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(reason, "reason");
        if (available) {
            Contracts.finite("seconds", seconds);
            if (seconds <= 0d || timestampNanos <= 0 || method == TtcMethod.NOT_AVAILABLE
                    || (method == TtcMethod.IMAGE_SCALE && quality != EvidenceQuality.LOW)
                    || quality == EvidenceQuality.UNAVAILABLE || reason != PhysicalReason.AVAILABLE) {
                throw new IllegalArgumentException("available TTC requires positive seconds and provenance");
            }
        } else if (!Double.isNaN(seconds) || method != TtcMethod.NOT_AVAILABLE
                || quality != EvidenceQuality.UNAVAILABLE || reason == PhysicalReason.AVAILABLE) {
            throw new IllegalArgumentException("unavailable TTC must use NaN/reason");
        }
    }

    public static TtcEstimate of(double seconds, TtcMethod method, EvidenceQuality quality, long ts) {
        return new TtcEstimate(true, seconds, method, quality, ts, PhysicalReason.AVAILABLE);
    }

    public static TtcEstimate unavailable(long ts, PhysicalReason reason) {
        return new TtcEstimate(false, Double.NaN, TtcMethod.NOT_AVAILABLE,
                EvidenceQuality.UNAVAILABLE, ts, reason);
    }
}
