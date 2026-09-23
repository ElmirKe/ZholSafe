package kz.zholsafe.ai.infer;

import java.util.List;

/**
 * ONNX implementation boundary. The only thing the core detector needs from a runtime:
 * describe the graph's I/O and run one float tensor. The Android module implements this over
 * ONNX Runtime ({@code ai.onnxruntime.OrtSession}); tests implement it with a scripted fake.
 * No runtime type ever crosses this interface.
 */
public interface TensorSession extends AutoCloseable {

    /** Static tensor metadata; dims may be -1 when dynamic. {@code elementType} is canonical. */
    record TensorInfo(String name, long[] shape, TensorElementType elementType) {
        public TensorInfo {
            java.util.Objects.requireNonNull(name, "name");
            java.util.Objects.requireNonNull(elementType, "elementType");
            shape = shape == null ? new long[0] : shape.clone();
        }
    }

    List<TensorInfo> inputs();

    List<TensorInfo> outputs();

    /** Human-readable execution provider actually in use, e.g. "CPU", "NNAPI". */
    String executionProvider();

    /**
     * Runs the graph. {@code input} is the reusable preprocessed tensor (row-major, shape =
     * {@code inputShape}). Returns the requested output flattened plus its concrete shape.
     * The returned array may be reused by the session on the next call.
     */
    Result run(String inputName, float[] input, long[] inputShape, String outputName) throws Exception;

    record Result(float[] data, long[] shape) { }

    @Override
    void close();
}
