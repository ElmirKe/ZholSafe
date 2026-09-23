package kz.zholsafe.ai;

/**
 * Minimal abstraction over an ONNX Runtime session.
 *
 * <p>Stage 2 will provide {@code OnnxRuntimeModel} backed by {@code ai.onnxruntime.OrtSession}.
 * Keeping this interface tiny lets the core module stay free of the ONNX Runtime dependency and
 * lets tests substitute a clearly-labelled fake.
 */
public interface OnnxModel extends AutoCloseable {

    /** Descriptor this session was created from. */
    ModelDescriptor descriptor();

    /**
     * Runs the network on an already-preprocessed input tensor.
     *
     * @param inputNchw float tensor in NCHW layout, length = 3 * inputHeight * inputWidth
     * @return raw output tensor(s) flattened; interpretation is detector-specific
     */
    float[][] run(float[] inputNchw) throws InferenceException;

    boolean isLoaded();

    @Override
    void close();

    /** Raised when a loaded model fails during a run. */
    class InferenceException extends Exception {
        public InferenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
