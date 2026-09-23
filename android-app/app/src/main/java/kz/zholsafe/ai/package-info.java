/**
 * Android bindings for the core detector: asset-backed model files ({@code AssetModelFiles}).
 * The ONNX Runtime {@code TensorSession} adapter ({@code OrtTensorSession}/{@code OrtSessionFactory})
 * lives in the shared pure-JVM {@code :ort-adapter} module (same package) so that the Android app
 * and the desktop smoke-test harness run the identical adapter. Model semantics (spec,
 * preprocessing, decoding, NMS, label mapping) live in the pure-Java core.
 */
package kz.zholsafe.ai;
