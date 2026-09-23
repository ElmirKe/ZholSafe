package kz.zholsafe.driver;

import kz.zholsafe.model.Contracts;

/**
 * Head orientation estimate in degrees. Use {@link #UNAVAILABLE} when no estimate exists;
 * consumers must check {@link #available()}.
 */
public record HeadPose(boolean available, float yawDeg, float pitchDeg, float rollDeg) {

    public static final HeadPose UNAVAILABLE = new HeadPose(false, Float.NaN, Float.NaN, Float.NaN);

    public HeadPose {
        if (available) {
            Contracts.finite("yawDeg", yawDeg);
            Contracts.finite("pitchDeg", pitchDeg);
            Contracts.finite("rollDeg", rollDeg);
        }
    }

    public static HeadPose of(float yawDeg, float pitchDeg, float rollDeg) {
        return new HeadPose(true, yawDeg, pitchDeg, rollDeg);
    }
}
