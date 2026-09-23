package kz.zholsafe.ai;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import kz.zholsafe.ai.infer.TensorSession;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link TensorSession} over ONNX Runtime (Java API, Android AAR). The only class in the app
 * that touches {@code ai.onnxruntime.*}. Input tensors are created from the caller's reusable
 * float[] (one direct-buffer copy per frame by ORT); the output is copied into a reusable float[]
 * so the ORT result can be closed immediately.
 */
final class OrtTensorSession implements TensorSession {

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

    private static List<TensorInfo> describe(Map<String, NodeInfo> infos) {
        List<TensorInfo> out = new ArrayList<>();
        for (NodeInfo n : infos.values()) {
            long[] shape = new long[0];
            String type = "unknown";
            if (n.getInfo() instanceof ai.onnxruntime.TensorInfo ti) {
                shape = ti.getShape();
                type = ti.type.toString().toLowerCase(java.util.Locale.ROOT); // e.g. "float"
            }
            out.add(new TensorInfo(n.getName(), shape, type));
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
            OnnxValue v = r.get(0);
            if (!(v instanceof OnnxTensor t)) {
                throw new IllegalStateException("output '" + outputName + "' is not a tensor: " + v.getType());
            }
            ai.onnxruntime.TensorInfo info = t.getInfo();
            long[] shape = info.getShape();
            FloatBuffer fb = t.getFloatBuffer();
            int n = fb.remaining();
            if (outScratch.length < n) {
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
        } catch (RuntimeException ignored) {
            // best effort
        }
    }
}
