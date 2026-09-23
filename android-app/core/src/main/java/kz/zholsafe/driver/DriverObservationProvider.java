package kz.zholsafe.driver;

import kz.zholsafe.ai.DetectionException;
import kz.zholsafe.ai.Frame;

/**
 * Stage 4.3 observation port: turns one driver-facing {@link Frame} into one
 * {@link DriverObservation}.
 *
 * <p>The temporal DriverGuard core ({@link DriverStateAnalyzer} → driver risk engine → fusion)
 * depends ONLY on this interface and on the resulting scalar contract — never on MediaPipe,
 * ML Kit, a specific neural network or the Android camera. Implementations shipped in this stage:
 * {@link SyntheticDriverObservationProvider} (deterministic, tests/demo). Expected later:
 * {@code MediaPipeDriverObservationProvider} (Google AI Edge face landmarks on Android; a model
 * lifecycle variant exists as {@link kz.zholsafe.ai.DriverDetector}) and replay providers.
 *
 * <p>Contract: called serially from one processing thread; must not retain {@code frame.data()}
 * after returning; must stamp the observation with {@link Frame#timestampNanos()} (source time);
 * must report quantities it cannot measure as explicitly unavailable instead of guessing them.
 */
public interface DriverObservationProvider {

    /** Stable identifier of the observation backend (e.g. "synthetic-script", "mediapipe-face"). */
    String sourceId();

    /**
     * Produces the observation for one frame; frame pixel data must not outlive this call. A
     * thrown {@link DetectionException} marks THIS observation attempt failed (counted by the
     * pipeline) — it must never be converted into a fabricated "all clear" observation.
     */
    DriverObservation provide(Frame frame) throws DetectionException;
}
