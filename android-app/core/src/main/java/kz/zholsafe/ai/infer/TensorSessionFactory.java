package kz.zholsafe.ai.infer;

import kz.zholsafe.ai.ModelNotAvailableException;

/**
 * Opens a {@link TensorSession} for a model file. Android implementation: ONNX Runtime with the
 * configured execution provider; the model path is an absolute file (copied from assets once).
 */
public interface TensorSessionFactory {

    TensorSession open(String modelPath) throws ModelNotAvailableException;

    /** Provider requested by configuration (e.g. "CPU"). Actual provider is reported by the session. */
    String requestedExecutionProvider();
}
