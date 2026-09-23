package kz.zholsafe.physical;

import kz.zholsafe.model.Contracts;

/** Camera ray proportional to (x,y,1): x right, y DOWN, z forward along optical axis. */
public record CameraRay(double x, double y) {
    public CameraRay {
        Contracts.finite("ray.x", x);
        Contracts.finite("ray.y", y);
    }
}
