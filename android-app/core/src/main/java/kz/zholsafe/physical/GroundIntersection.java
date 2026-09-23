package kz.zholsafe.physical;

import kz.zholsafe.model.Contracts;

/** Ground contact: opticalDepthMeters is the camera-ray parameter λ (camera z-depth in metres).
 * Other fields are horizontal road-plane coordinates relative to camera: +right and +forward. */
public record GroundIntersection(double opticalDepthMeters, double groundRightMeters,
                                 double groundForwardMeters) {
    public GroundIntersection {
        Contracts.finite("opticalDepthMeters", opticalDepthMeters);
        Contracts.finite("groundRightMeters", groundRightMeters);
        Contracts.finite("groundForwardMeters", groundForwardMeters);
        if (opticalDepthMeters <= 0d || groundForwardMeters <= 0d) {
            throw new IllegalArgumentException("intersection must be in front of camera/vehicle");
        }
    }
}
