package kz.zholsafe.physical;

import java.util.Objects;
import java.util.Optional;

/** Locally flat road plane; camera ray (x,y,1) is rotated by positive DOWN pitch. */
public final class GroundPlaneGeometry {
    private GroundPlaneGeometry() { }

    public static Optional<GroundIntersection> intersect(CameraCalibration cal, CameraRay ray,
                                                         double horizonRayMargin) {
        Objects.requireNonNull(cal, "cal");
        Objects.requireNonNull(ray, "ray");
        if (!Double.isFinite(horizonRayMargin) || horizonRayMargin <= 0d) {
            throw new IllegalArgumentException("horizonRayMargin must be finite and > 0");
        }
        double sin = Math.sin(cal.pitchRadians());
        double cos = Math.cos(cal.pitchRadians());
        // Downward ray component; near-zero = horizon singularity. Road plane is h below camera.
        double down = sin + ray.y() * cos;
        double forward = cos - ray.y() * sin;
        if (!Double.isFinite(down) || down <= horizonRayMargin || forward <= 0d) return Optional.empty();
        double lambda = cal.cameraHeightMeters() / down;
        double rightMeters = lambda * ray.x();
        double forwardMeters = lambda * forward;
        if (!Double.isFinite(lambda) || !Double.isFinite(rightMeters)
                || !Double.isFinite(forwardMeters) || lambda <= 0d || forwardMeters <= 0d) {
            return Optional.empty();
        }
        return Optional.of(new GroundIntersection(lambda, rightMeters, forwardMeters));
    }
}
