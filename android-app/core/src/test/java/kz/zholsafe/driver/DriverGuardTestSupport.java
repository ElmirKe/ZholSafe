package kz.zholsafe.driver;

import kz.zholsafe.ai.Frame;

import java.nio.ByteBuffer;

/** Shared builders for DriverGuard JVM tests. All timestamps are DRIVER SOURCE time (nanos). */
public final class DriverGuardTestSupport {

    public static final long T0 = 1_000_000_000L; // 1 s — keeps source time strictly positive
    public static final float OPEN = 0.95f;
    public static final float CLOSED = 0.0f;

    private DriverGuardTestSupport() { }

    public static long t(double seconds) {
        return T0 + Math.round(seconds * 1_000_000_000d);
    }

    public static long tm(long millis) {
        return T0 + millis * 1_000_000L;
    }

    /** Defaults with an overridden PERCLOS window / gap / coverage / hard cap. */
    public static kz.zholsafe.config.DriverGuardConfig config(
            double windowSeconds, double gapSeconds, float coverage, int maxObservations) {
        kz.zholsafe.config.DriverGuardConfig d = kz.zholsafe.config.DriverGuardConfig.defaults();
        return new kz.zholsafe.config.DriverGuardConfig(d.eyeClosedThreshold(),
                d.eyePartiallyClosedThreshold(), d.minimumObservationConfidence(),
                d.prolongedClosureCautionSeconds(), d.prolongedClosureWarningSeconds(),
                d.prolongedClosureCriticalSeconds(), windowSeconds, coverage,
                d.perclosCautionThreshold(), d.perclosWarningThreshold(), d.mouthOpenThreshold(),
                d.minimumYawnDurationSeconds(), d.headYawThresholdDegrees(),
                d.headDownPitchThresholdDegrees(), d.headAwayDurationSeconds(),
                d.persistentFaceLossSeconds(), d.eyeVisibilityLostSeconds(), gapSeconds,
                maxObservations);
    }

    /** Face with both eyes at the same openness; no mouth/pose evidence; high confidence. */
    public static DriverObservation faceEyes(long ts, float openness) {
        return new DriverObservation(ts, true, true, openness, openness,
                false, Float.NaN, HeadPose.UNAVAILABLE, 0.95f);
    }

    /** Face with eyes, mouth score and head pose; high confidence. */
    public static DriverObservation faceFull(long ts, float openness, float mouthScore, HeadPose pose) {
        return new DriverObservation(ts, true, true, openness, openness,
                true, mouthScore, pose, 0.95f);
    }

    /** Face visible, eyes NOT evaluable (landmarks missing). */
    public static DriverObservation faceNoEyes(long ts) {
        return new DriverObservation(ts, true, false, Float.NaN, Float.NaN,
                false, Float.NaN, HeadPose.UNAVAILABLE, 0.95f);
    }

    /** Face with eyes at the given openness but LOW confidence (below the default 0.5 minimum). */
    public static DriverObservation faceEyesLowConfidence(long ts, float openness) {
        return new DriverObservation(ts, true, true, openness, openness,
                false, Float.NaN, HeadPose.UNAVAILABLE, 0.30f);
    }

    public static DriverObservation noFace(long ts) {
        return DriverObservation.noFace(ts);
    }

    /** Minimal valid test frame; pixel content is never inspected. */
    public static Frame frameAt(long ts) {
        int size = Frame.packedSize(Frame.PixelFormat.NV21, 8, 8);
        return new Frame(8, 8, Frame.PixelFormat.NV21, ByteBuffer.allocate(size), 0, ts,
                Frame.CameraSource.TEST);
    }

    /** Drives an analyzer over integer 100 ms source-time steps. */
    public static DriverState feed(DriverStateAnalyzer analyzer, double fromSeconds, double toSeconds,
                            java.util.function.Function<Long, DriverObservation> source) {
        DriverState state = analyzer.current();
        for (long ts = t(fromSeconds); ts <= t(toSeconds); ts += 100_000_000L) {
            state = analyzer.update(source.apply(ts));
        }
        return state;
    }
}
