package kz.zholsafe.ai;

import kz.zholsafe.driver.DriverObservation;
import kz.zholsafe.driver.DriverObservationProvider;

/**
 * Driver observation backend with model lifecycle (load/state), mirroring {@link RoadDetector}
 * lifecycle semantics. Stage 4.3. Extends the temporal core's backend-agnostic port
 * {@link DriverObservationProvider}; adds {@link #load()}/{@link #state()} for model-backed
 * implementations, e.g. the intended Google AI Edge / MediaPipe face-landmarks backend
 * ({@code MediaPipeDriverObservationProvider}, Android wiring in a future stage — landmark
 * backend NOT VERIFIED in this repository).
 *
 * <p>Coordinate convention (binding, mirrors {@link RoadDetector}): any coordinates an
 * implementation maps into continuous observation values (openness, {@link
 * kz.zholsafe.driver.HeadPose} angles) are derived from the UPRIGHT camera buffer
 * (after {@link Frame#rotationDegrees()}). Raw camera-buffer coordinates never leak out —
 * the port speaks only continuous scalar observations; no bitmaps or landmark tensors.
 *
 * <p>Semantics (same honesty rule as {@link RoadDetector}): {@link
 * DriverObservation#noFace(long)} means "model ran, found no face" (NO FACE = monitoring loss,
 * never "eyes closed" or "asleep"). A thrown {@link DetectionException} or non-READY state
 * means OBSERVATION UNAVAILABLE. Failures must never be disguised as real measurements.
 *
 * <p>UNKNOWN eye state, mouth or pose signals are honest machine states (governed by
 * {@link kz.zholsafe.config.DriverGuardConfig#minimumObservationConfidence()} and the
 * availability flags), never an error to hide.
 */
public interface DriverDetector extends DriverObservationProvider, AutoCloseable {

    /** Loads the model synchronously. Fails fast with a diagnostic; state becomes READY or ERROR. */
    void load() throws ModelNotAvailableException;

    DetectorState state();

    default boolean isReady() {
        return state() == DetectorState.READY;
    }

    /**
     * Produces one observation from one frame. Called serially from the driver processing thread.
     * Must not retain {@code frame.data()} or any derived tensor/bitmap after returning.
     */
    @Override
    DriverObservation provide(Frame frame) throws DetectionException;

    @Override
    void close();
}
