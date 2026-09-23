package kz.zholsafe.trajectory;

import kz.zholsafe.model.Contracts;

import java.util.Objects;

/**
 * Centre motion in NORMALIZED image coordinates. vx: frame-width fractions/second;
 * vy: frame-height fractions/second (+Y DOWN). magnitude: Euclidean norm of these two
 * normalized rates, in normalized-frame fractions/second. Not metres/second.
 */
public record ImageMotion(boolean available, double velocityXFrameWidthsPerSecond,
        double velocityYFrameHeightsPerSecond, double normalizedSpeedPerSecond,
        ImageDirection direction) {
    private static final ImageMotion UNAVAILABLE = new ImageMotion(false, Double.NaN, Double.NaN,
            Double.NaN, ImageDirection.UNCERTAIN);

    public ImageMotion {
        Objects.requireNonNull(direction, "direction");
        if (available) {
            Contracts.finite("velocityXFrameWidthsPerSecond", velocityXFrameWidthsPerSecond);
            Contracts.finite("velocityYFrameHeightsPerSecond", velocityYFrameHeightsPerSecond);
            Contracts.finite("normalizedSpeedPerSecond", normalizedSpeedPerSecond);
            if (normalizedSpeedPerSecond < 0d
                    || Double.compare(normalizedSpeedPerSecond,
                            Math.hypot(velocityXFrameWidthsPerSecond, velocityYFrameHeightsPerSecond)) != 0) {
                throw new IllegalArgumentException("normalizedSpeedPerSecond must equal hypot(vx, vy)");
            }
            if (direction == ImageDirection.UNCERTAIN) throw new IllegalArgumentException("available motion requires direction");
        } else if (!Double.isNaN(velocityXFrameWidthsPerSecond) || !Double.isNaN(velocityYFrameHeightsPerSecond)
                || !Double.isNaN(normalizedSpeedPerSecond) || direction != ImageDirection.UNCERTAIN) {
            throw new IllegalArgumentException("unavailable image motion must use NaN/UNCERTAIN");
        }
    }

    public static ImageMotion unavailable() { return UNAVAILABLE; }
}
