package kz.zholsafe.ai;

import kz.zholsafe.driver.DriverObservation;

/**
 * Driver-facing camera analysis (Stage 3). Interface only; no implementation in Stage 2.
 * Mirrors {@link RoadDetector}: Android-independent, fail-fast load, explicit state.
 */
public interface DriverDetector extends AutoCloseable {

    void load() throws ModelNotAvailableException;

    DetectorState state();

    default boolean isReady() {
        return state() == DetectorState.READY;
    }

    DriverObservation analyze(Frame frame) throws DetectionException;

    @Override
    void close();
}
