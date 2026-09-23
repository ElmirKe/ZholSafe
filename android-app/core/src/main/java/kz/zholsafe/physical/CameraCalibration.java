package kz.zholsafe.physical;

import kz.zholsafe.model.Contracts;

import java.util.Objects;

/**
 * Intrinsics in UPRIGHT source pixels and extrinsics: camera height above locally planar road,
 * pitch positive DOWN from horizontal. No roll/yaw compensation. No auto/default calibration:
 * an instance must be provided explicitly by the caller. FOV-derived intrinsics are APPROXIMATE.
 */
public record CameraCalibration(int imageWidth, int imageHeight, double fxPixels, double fyPixels,
        double cxPixels, double cyPixels, double cameraHeightMeters, double pitchRadians,
        CalibrationSource source) {
    public CameraCalibration {
        if (imageWidth < 2 || imageHeight < 2) throw new IllegalArgumentException("image dimensions must be >= 2");
        positive("fxPixels", fxPixels);
        positive("fyPixels", fyPixels);
        Contracts.range("cxPixels", cxPixels, 0d, imageWidth - 1d);
        Contracts.range("cyPixels", cyPixels, 0d, imageHeight - 1d);
        positive("cameraHeightMeters", cameraHeightMeters);
        Contracts.finite("pitchRadians", pitchRadians);
        if (pitchRadians <= -Math.PI / 2d || pitchRadians >= Math.PI / 2d) {
            throw new IllegalArgumentException("pitch must be strictly between -pi/2 and pi/2");
        }
        Objects.requireNonNull(source, "source");
    }

    private static void positive(String name, double value) {
        Contracts.finite(name, value);
        if (value <= 0d) throw new IllegalArgumentException(name + " must be > 0");
    }

    public static CameraCalibration measured(int width, int height, double fx, double fy, double cx,
                                              double cy, double cameraHeight, double pitch) {
        return new CameraCalibration(width, height, fx, fy, cx, cy, cameraHeight, pitch,
                CalibrationSource.MEASURED_INTRINSICS);
    }

    /** Square-pixel/centred-principal-point assumption; NOT measured intrinsics. */
    public static CameraCalibration fromVerticalFov(int width, int height, double verticalFovRadians,
                                                    double cameraHeight, double pitch) {
        Contracts.finite("verticalFovRadians", verticalFovRadians);
        if (verticalFovRadians <= 0d || verticalFovRadians >= Math.PI) {
            throw new IllegalArgumentException("vertical FOV must be in (0, pi)");
        }
        double focal = height / (2d * Math.tan(verticalFovRadians / 2d));
        return new CameraCalibration(width, height, focal, focal, width / 2d, height / 2d,
                cameraHeight, pitch, CalibrationSource.FOV_DERIVED_APPROXIMATE);
    }

    /** Pinhole x=(u-cx)/fx, y=(v-cy)/fy. Coordinates must refer to this upright image. */
    public CameraRay ray(double uPixels, double vPixels) {
        Contracts.finite("uPixels", uPixels);
        Contracts.finite("vPixels", vPixels);
        if (uPixels < 0d || uPixels >= imageWidth || vPixels < 0d || vPixels >= imageHeight) {
            throw new IllegalArgumentException("ray pixel is outside calibration image");
        }
        return new CameraRay((uPixels - cxPixels) / fxPixels, (vPixels - cyPixels) / fyPixels);
    }
}
