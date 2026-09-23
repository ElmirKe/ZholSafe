package kz.zholsafe.driver;

/**
 * Head orientation estimate in degrees. Use {@link #UNAVAILABLE} when no estimate exists;
 * consumers must check {@link #available()}.
 */
public record HeadPose(boolean available, float yawDeg, float pitchDeg, float rollDeg) {

    public static final HeadPose UNAVAILABLE = new HeadPose(false, Float.NaN, Float.NaN, Float.NaN);

    public static HeadPose of(float yawDeg, float pitchDeg, float rollDeg) {
        return new HeadPose(true, yawDeg, pitchDeg, rollDeg);
    }
}
