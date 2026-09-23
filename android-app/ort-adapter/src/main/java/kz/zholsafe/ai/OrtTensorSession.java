package kz.zholsafe.ai;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.ValueInfo;

import kz.zholsafe.ai.infer.TensorElementType;
import kz.zholsafe.ai.infer.TensorSession;
import kz.zholsafe.logging.ZLog;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link TensorSession} over ONNX Runtime (Java API; runtime artifact is {@code onnxruntime-android} in the app and {@code onnxruntime} on the JVM). The only class
 * besides {@link OrtSessionFactory} that touches {@code ai.onnxruntime.*}.
 *
 * <p>API usage audited against onnxruntime v1.19.2 Java sources (Stage 2.1):
 * {@code OnnxTensor.createTensor(OrtEnvironment, FloatBuffer, long[])} (non-direct buffers are
 * copied into a direct buffer owned by the tensor), {@code OrtSession.run(Map, Set)} with
 * requested output names, {@code Result.get(int)} indexed in requested order,
 * {@code OnnxTensorLike.getInfo()} → {@code TensorInfo.type} ({@code OnnxJavaType}) +
 * {@code getShape()}, {@code OnnxTensor.getFloatBuffer()} (returns a heap COPY for FLOAT),
 * {@code OrtSession.close() throws OrtException}. Tensor element types are mapped explicitly via
 * {@link #canonicalType(OnnxJavaType)} — never via {@code toString()}.
 */
final class OrtTensorSession implements TensorSession {

    private static final String TAG = "OrtTensorSession";

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String provider;
    private final List<TensorInfo> inputs;
    private final List<TensorInfo> outputs;
    private float[] outScratch = new float[0];

    OrtTensorSession(OrtEnvironment env, OrtSession session, String provider) throws OrtException {
        this.env = env;
        this.session = session;
        this.provider = provider;
        this.inputs = describe(session.getInputInfo());
        this.outputs = describe(session.getOutputInfo());
    }

    /** Explicit mapping from the runtime's Java type enum to the canonical type. */
    static TensorElementType canonicalType(OnnxJavaType t) {
        if (t == null) {
            return TensorElementType.UNKNOWN;
        }
        switch (t) {
            case FLOAT: return TensorElementType.FLOAT32;
            case FLOAT16: return TensorElementType.FLOAT16;
            case BFLOAT16: return TensorElementType.BFLOAT16;
            case DOUBLE: return TensorElementType.FLOAT64;
            case INT8: return TensorElementType.INT8;
            case UINT8: return TensorElementType.UINT8;
            case INT16: return TensorElementType.INT16;
            case INT32: return TensorElementType.INT32;
            case INT64: return TensorElementType.INT64;
            case BOOL: return TensorElementType.BOOL;
            case STRING: return TensorElementType.STRING;
            case UNKNOWN:
            default: return TensorElementType.UNKNOWN;
        }
    }

    private static List<TensorInfo> describe(Map<String, NodeInfo> infos) {
        List<TensorInfo> out = new ArrayList<>(infos.size());
        for (NodeInfo n : infos.values()) {
            ValueInfo vi = n.getInfo();
            if (vi instanceof ai.onnxruntime.TensorInfo ti) {
                out.add(new TensorInfo(n.getName(), ti.getShape(), canonicalType(ti.type)));
            } else {
                // Sequence/map/sparse outputs are not tensors; report as UNKNOWN so validation fails clearly.
                out.add(new TensorInfo(n.getName(), new long[0], TensorElementType.UNKNOWN));
            }
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public List<TensorInfo> inputs() {
        return inputs;
    }

    @Override
    public List<TensorInfo> outputs() {
        return outputs;
    }

    @Override
    public String executionProvider() {
        return provider;
    }

    @Override
    public Result run(String inputName, float[] input, long[] inputShape, String outputName) throws Exception {
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), inputShape);
             OrtSession.Result r = session.run(Collections.singletonMap(inputName, in), Collections.singleton(outputName))) {
            OnnxValue v = r.get(0); // single requested output → index 0
            if (!(v instanceof OnnxTensor t)) {
                throw new IllegalStateException("output '" + outputName + "' is not a tensor: " + v.getType());
            }
            ai.onnxruntime.TensorInfo info = t.getInfo();
            if (canonicalType(info.type) != TensorElementType.FLOAT32) {
                throw new IllegalStateException("output '" + outputName + "' element type " + info.type
                        + " is not FLOAT32");
            }
            long[] shape = info.getShape();
            FloatBuffer fb = t.getFloatBuffer(); // heap copy owned by us; safe after Result.close()
            int n = fb.remaining();
            if (outScratch.length != n) {
                outScratch = new float[n];
            }
            fb.get(outScratch, 0, n);
            return new Result(outScratch, shape);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException | RuntimeException e) {
            ZLog.w(TAG, "session close: " + e.getMessage());
        }
    }
}
