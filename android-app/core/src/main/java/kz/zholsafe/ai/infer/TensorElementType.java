package kz.zholsafe.ai.infer;

/**
 * Canonical, runtime-independent tensor element type. {@link TensorSession} implementations map
 * their runtime's type enum to this explicitly; the detector validates against it. Stage 2
 * supports FLOAT32 only; every other value fails fast at load with a clear diagnostic.
 */
public enum TensorElementType {
    FLOAT32,
    FLOAT16,
    BFLOAT16,
    FLOAT64,
    INT8,
    UINT8,
    INT16,
    INT32,
    INT64,
    BOOL,
    STRING,
    UNKNOWN
}
