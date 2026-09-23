package kz.zholsafe.ai;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.providers.NNAPIFlags;

import kz.zholsafe.ai.infer.TensorSession;
import kz.zholsafe.ai.infer.TensorSessionFactory;
import kz.zholsafe.logging.ZLog;

import java.util.EnumSet;
import java.util.Locale;

/**
 * Opens ONNX Runtime sessions with the configured execution provider.
 *
 * <p>"CPU" is the portable default. "NNAPI" is accepted as a request and falls back to CPU when
 * unavailable; the session reports the provider actually configured. No performance claim is
 * made for NNAPI until measured on device.
 */
public final class OrtSessionFactory implements TensorSessionFactory {

    private static final String TAG = "OrtSessionFactory";
    private final String requested;
    private final int intraOpThreads;

    public OrtSessionFactory(String requestedProvider, int intraOpThreads) {
        this.requested = requestedProvider == null ? "CPU" : requestedProvider.toUpperCase(Locale.ROOT);
        this.intraOpThreads = Math.max(1, intraOpThreads);
    }

    @Override
    public TensorSession open(String modelPath) throws ModelNotAvailableException {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        String provider = "CPU";
        try {
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(intraOpThreads);
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            if ("NNAPI".equals(requested)) {
                try {
                    opts.addNnapi(EnumSet.noneOf(NNAPIFlags.class));
                    provider = "NNAPI+CPU";
                } catch (OrtException e) {
                    ZLog.w(TAG, "NNAPI unavailable, using CPU: " + e.getMessage());
                }
            }
            OrtSession session = env.createSession(modelPath, opts);
            return new OrtTensorSession(env, session, provider);
        } catch (OrtException e) {
            throw new ModelNotAvailableException(modelPath, "ONNX Runtime could not open the model: " + e.getMessage(), e);
        }
    }

    @Override
    public String requestedExecutionProvider() {
        return requested;
    }
}
