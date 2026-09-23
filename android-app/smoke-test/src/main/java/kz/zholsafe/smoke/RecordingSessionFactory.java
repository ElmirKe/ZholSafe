package kz.zholsafe.smoke;

import kz.zholsafe.ai.ModelNotAvailableException;
import kz.zholsafe.ai.infer.TensorSession;
import kz.zholsafe.ai.infer.TensorSessionFactory;

import java.util.List;

/**
 * Decorates the REAL {@link TensorSessionFactory} (ONNX Runtime) so the harness can (a) prove that
 * {@code session.run} was reached and (b) keep a copy of the last raw output tensor for
 * diagnostics (raw candidate counts, reference comparison). It never alters inputs or outputs;
 * the detector still consumes the runtime's result directly.
 */
final class RecordingSessionFactory implements TensorSessionFactory {

    private final TensorSessionFactory delegate;
    private volatile Recording session;

    RecordingSessionFactory(TensorSessionFactory delegate) {
        this.delegate = delegate;
    }

    Recording session() {
        return session;
    }

    @Override
    public TensorSession open(String modelPath) throws ModelNotAvailableException {
        Recording r = new Recording(delegate.open(modelPath));
        session = r;
        return r;
    }

    @Override
    public String requestedExecutionProvider() {
        return delegate.requestedExecutionProvider();
    }

    static final class Recording implements TensorSession {
        private final TensorSession real;
        private long runCount;
        private float[] lastOutput;
        private long[] lastShape;

        Recording(TensorSession real) {
            this.real = real;
        }

        long runCount() { return runCount; }

        /** Copy of the most recent raw output (null before the first run). */
        float[] lastOutput() { return lastOutput; }

        long[] lastShape() { return lastShape; }

        TensorSession real() { return real; }

        @Override public List<TensorInfo> inputs() { return real.inputs(); }
        @Override public List<TensorInfo> outputs() { return real.outputs(); }
        @Override public String executionProvider() { return real.executionProvider(); }

        @Override
        public Result run(String inputName, float[] input, long[] inputShape, String outputName) throws Exception {
            Result r = real.run(inputName, input, inputShape, outputName);
            runCount++;
            lastOutput = r.data().clone();
            lastShape = r.shape().clone();
            return r;
        }

        @Override public void close() { real.close(); }
    }
}
