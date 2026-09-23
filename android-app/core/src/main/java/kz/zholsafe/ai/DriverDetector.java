package kz.zholsafe.ai;

import kz.zholsafe.driver.DriverObservation;

/**
 * DriverGuard per-frame analyzer contract: driver-camera frame in, raw observation out.
 *
 * <p>Temporal reasoning (eye-closure duration, PERCLOS) is NOT done here — it lives in
 * {@link kz.zholsafe.driver.DrowsinessAnalyzer}, which is pure Java and unit-testable.
 * Stage 3 provides the real implementation.
 */
public interface DriverDetector extends AutoCloseable {

    void load() throws ModelNotAvailableException;

    boolean isReady();

    DriverObservation analyze(Frame frame) throws OnnxModel.InferenceException;

    @Override
    void close();
}
