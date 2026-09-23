package kz.zholsafe.trajectory;

import kz.zholsafe.model.Contracts;

import java.util.Objects;

/**
 * Apparent image footprint: normalizedArea = box area / (frame width * frame height), and
 * logAreaRatePerSecond = regression slope of log(normalizedArea) in 1/second. This is NOT depth,
 * metric range, physical speed or TTC. Log rate is dimensionless per second.
 */
public record ImageScaleTrend(boolean available, double normalizedArea, double logAreaRatePerSecond,
                              ScaleChange change) {
    private static final ImageScaleTrend UNAVAILABLE = new ImageScaleTrend(false, Double.NaN,
            Double.NaN, ScaleChange.UNCERTAIN);

    public ImageScaleTrend {
        Objects.requireNonNull(change, "change");
        if (available) {
            Contracts.finite("normalizedArea", normalizedArea);
            if (normalizedArea <= 0d || normalizedArea > 1d) {
                throw new IllegalArgumentException("normalizedArea must be in (0,1]");
            }
            Contracts.finite("logAreaRatePerSecond", logAreaRatePerSecond);
        } else if (!Double.isNaN(normalizedArea) || !Double.isNaN(logAreaRatePerSecond)
                || change != ScaleChange.UNCERTAIN) {
            throw new IllegalArgumentException("unavailable scale trend must use NaN/UNCERTAIN");
        }
    }

    public static ImageScaleTrend unavailable() { return UNAVAILABLE; }
}
