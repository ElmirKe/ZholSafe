package kz.zholsafe.ai;

import kz.zholsafe.ai.decode.DetectionDecoder;
import kz.zholsafe.ai.decode.IncompatibleOutputException;
import kz.zholsafe.ai.decode.Nms;
import kz.zholsafe.ai.decode.RawDetection;
import kz.zholsafe.ai.infer.ModelFiles;
import kz.zholsafe.ai.infer.TensorElementType;
import kz.zholsafe.ai.infer.TensorSession;
import kz.zholsafe.ai.infer.TensorSessionFactory;
import kz.zholsafe.ai.preprocess.LetterboxTransform;
import kz.zholsafe.ai.preprocess.Nv21Preprocessor;
import kz.zholsafe.ai.spec.ModelSpec;
import kz.zholsafe.ai.spec.ModelSpecParser;
import kz.zholsafe.logging.ZLog;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Model-agnostic ONNX road detector. All model knowledge comes from {@link ModelSpec}; all runtime
 * knowledge is behind {@link TensorSession}. Pure Java — the Android module only supplies the
 * {@link TensorSessionFactory} (ONNX Runtime) and {@link ModelFiles} (assets).
 *
 * <pre>
 * load():   spec → labels → sha256 (optional) → session → validate I/O → READY | ERROR
 * detect(): preprocess (rotate+letterbox+normalise) → session.run → decoder → [NMS] →
 *           label map (unsupported → dropped) → letterbox inverse → clamp → Detection[]
 * </pre>
 *
 * <p>Timings use the injected monotonic clock; {@link Frame#timestampNanos()} is only copied into
 * each {@link Detection}. Coordinates follow the {@link RoadDetector} convention (upright pixels).
 */
public final class OnnxRoadDetector implements RoadDetector {

    private static final String TAG = "OnnxDetector";
    private static final String SPEC_FILE = "model-spec.json";
    /** After this many consecutive inference failures the detector goes to ERROR (fatal). */
    static final int MAX_CONSECUTIVE_FAILURES = 25;

    private final String modelDir;
    private final ModelFiles files;
    private final TensorSessionFactory sessions;
    private final LongSupplier clock;

    private volatile DetectorState state = DetectorState.NOT_LOADED;
    private volatile String stateDetail = "";
    private ModelSpec spec;
    private LabelMap labelMap;
    private DetectionDecoder decoder;
    private Nv21Preprocessor preprocessor;
    private TensorSession session;
    private String inputName;
    private String outputName;
    private long[] inputShape;
    private boolean firstInferenceValidated;
    private int consecutiveFailures;
    private final List<RawDetection> scratch = new ArrayList<>(512);
    private volatile DetectorTimings lastTimings = DetectorTimings.ZERO;

    public OnnxRoadDetector(String modelDir, ModelFiles files, TensorSessionFactory sessions) {
        this(modelDir, files, sessions, System::nanoTime);
    }

    public OnnxRoadDetector(String modelDir, ModelFiles files, TensorSessionFactory sessions, LongSupplier clock) {
        this.modelDir = Objects.requireNonNull(modelDir);
        this.files = Objects.requireNonNull(files);
        this.sessions = Objects.requireNonNull(sessions);
        this.clock = Objects.requireNonNull(clock);
    }

    // ---------------------------------------------------------------- load

    @Override
    public synchronized void load() throws ModelNotAvailableException {
        if (state == DetectorState.CLOSED) {
            throw new ModelNotAvailableException(modelDir, "detector closed");
        }
        setState(DetectorState.LOADING, "");
        try {
            spec = readSpec();
            labelMap = readLabels(spec);
            decoder = DetectionDecoder.forType(spec.decoder());
            verifyChecksum(spec);
            String modelPath = files.absolutePath(join(modelDir, spec.modelFile()));
            session = sessions.open(modelPath);
            validateSessionIo(session, spec);
            preprocessor = new Nv21Preprocessor(spec);
            inputShape = spec.inputShape().stream().mapToLong(Long::longValue).toArray();
            firstInferenceValidated = false;
            consecutiveFailures = 0;
            setState(DetectorState.READY, "");
            ZLog.i(TAG, "loaded " + spec.modelId() + " decoder=" + spec.decoder() + " provider="
                    + session.executionProvider() + " classes=" + labelMap.supportedClasses()
                    + (labelMap.unmappedLabels().isEmpty() ? "" : " ignoredLabels=" + labelMap.unmappedLabels().size()));
        } catch (ModelNotAvailableException e) {
            fail(e.getMessage());
            throw e;
        } catch (IncompatibleOutputException | IllegalArgumentException | IOException e) {
            fail(e.getMessage());
            throw new ModelNotAvailableException(modelDir, e.getMessage(), e);
        } catch (RuntimeException e) {
            fail(e.toString());
            throw new ModelNotAvailableException(modelDir, "load failed: " + e, e);
        }
    }

    private ModelSpec readSpec() throws IOException, ModelNotAvailableException {
        String path = join(modelDir, SPEC_FILE);
        if (!files.exists(path)) {
            throw new ModelNotAvailableException(path, "model-spec.json missing (see models/README.md)");
        }
        return ModelSpecParser.parse(readAll(path));
    }

    private LabelMap readLabels(ModelSpec s) throws IOException, ModelNotAvailableException {
        String path = join(modelDir, s.labelsFile());
        if (!files.exists(path)) {
            throw new ModelNotAvailableException(path, "labels file missing");
        }
        List<String> labels = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(files.open(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    labels.add(t);
                }
            }
        }
        if (labels.size() != s.numClasses()) {
            throw new ModelNotAvailableException(path, "labels file has " + labels.size()
                    + " labels but model-spec numClasses=" + s.numClasses());
        }
        LabelMap map = new LabelMap(labels, s.labelAliases());
        if (map.supportedClasses().isEmpty()) {
            throw new ModelNotAvailableException(path, "no label maps to a canonical ObjectClass");
        }
        return map;
    }

    private void verifyChecksum(ModelSpec s) throws IOException, ModelNotAvailableException {
        String path = join(modelDir, s.modelFile());
        if (!files.exists(path)) {
            throw new ModelNotAvailableException(path, "ONNX file missing — place a legitimate export here (models/README.md)");
        }
        if (s.sha256() == null || s.sha256().isBlank()) {
            return;
        }
        String actual = sha256(files.open(path));
        if (!actual.equalsIgnoreCase(s.sha256().trim())) {
            throw new ModelNotAvailableException(path, "sha256 mismatch: spec=" + s.sha256() + " file=" + actual
                    + " (model-spec.json does not belong to this model.onnx)");
        }
    }

    static void validateSessionIo(TensorSession session, ModelSpec s) throws IncompatibleOutputException {
        List<TensorSession.TensorInfo> ins = session.inputs();
        List<TensorSession.TensorInfo> outs = session.outputs();
        if (ins.isEmpty()) throw new IncompatibleOutputException("model has no inputs");
        if (outs.isEmpty()) throw new IncompatibleOutputException("model has no outputs");
        TensorSession.TensorInfo in;
        if (s.inputName() != null) {
            in = ins.stream().filter(i -> i.name().equals(s.inputName())).findFirst()
                    .orElseThrow(() -> new IncompatibleOutputException("input '" + s.inputName() + "' not found; inputs=" + names(ins)));
        } else {
            if (ins.size() != 1) throw new IncompatibleOutputException("model has " + ins.size() + " inputs; spec must name one: " + names(ins));
            in = ins.get(0);
        }
        if (in.elementType() != TensorElementType.FLOAT32) {
            throw new IncompatibleOutputException("input '" + in.name() + "' element type " + in.elementType()
                    + " unsupported; Stage 2 requires " + TensorElementType.FLOAT32
                    + " (re-export the model in FP32 or add a typed input path)");
        }
        long[] want = s.inputShape().stream().mapToLong(Long::longValue).toArray();
        long[] got = in.shape();
        if (got.length != want.length) {
            throw new IncompatibleOutputException("input rank " + got.length + " != " + want.length + " " + Arrays.toString(got));
        }
        for (int i = 0; i < want.length; i++) {
            if (got[i] != -1 && got[i] != want[i]) {
                throw new IncompatibleOutputException("input shape " + Arrays.toString(got) + " != spec " + Arrays.toString(want)
                        + " (layout " + s.layout() + ", " + s.inputWidth() + "x" + s.inputHeight() + ")");
            }
        }
        TensorSession.TensorInfo out;
        if (s.outputName() != null) {
            out = outs.stream().filter(o -> o.name().equals(s.outputName())).findFirst()
                    .orElseThrow(() -> new IncompatibleOutputException("output '" + s.outputName() + "' not found; outputs=" + names(outs)));
        } else {
            out = outs.get(0);
        }
        if (out.elementType() != TensorElementType.FLOAT32) {
            throw new IncompatibleOutputException("output '" + out.name() + "' element type " + out.elementType()
                    + " unsupported; decoders read " + TensorElementType.FLOAT32 + " only");
        }
        DetectionDecoder.forType(s.decoder()).validateShape(out.shape(), s);
    }

    private static String names(List<TensorSession.TensorInfo> l) {
        List<String> n = new ArrayList<>();
        for (TensorSession.TensorInfo t : l) n.add(t.name() + Arrays.toString(t.shape()));
        return n.toString();
    }

    // ---------------------------------------------------------------- detect

    @Override
    public List<Detection> detect(Frame frame) throws DetectionException {
        if (state != DetectorState.READY) {
            throw new DetectionException("detector not ready: " + state + (stateDetail.isEmpty() ? "" : " — " + stateDetail));
        }
        long t0 = clock.getAsLong();
        LetterboxTransform lb;
        try {
            lb = preprocessor.process(frame);
        } catch (RuntimeException e) {
            throw failure(new DetectionException("preprocess failed: " + e, e));
        }
        long t1 = clock.getAsLong();
        TensorSession.Result res;
        try {
            res = session.run(inputName(), preprocessor.tensor(), inputShape, outputName());
        } catch (Exception e) {
            throw failure(new DetectionException("inference failed: " + e, e));
        }
        long t2 = clock.getAsLong();
        List<Detection> out;
        try {
            out = postprocess(res, lb, frame.timestampNanos());
        } catch (IncompatibleOutputException e) {
            fail(e.getMessage()); // shape mismatch at runtime is fatal, not per-frame
            throw new DetectionException(e.getMessage(), e);
        }
        long t3 = clock.getAsLong();
        lastTimings = new DetectorTimings(t1 - t0, t2 - t1, t3 - t2);
        consecutiveFailures = 0;
        return out;
    }

    private List<Detection> postprocess(TensorSession.Result res, LetterboxTransform lb, long frameTs)
            throws IncompatibleOutputException {
        if (!firstInferenceValidated) {
            decoder.validateShape(res.shape(), spec);
            firstInferenceValidated = true;
        }
        scratch.clear();
        decoder.decode(res.data(), res.shape(), spec, scratch);
        List<RawDetection> kept = decoder.requiresNms()
                ? Nms.classAware(scratch, spec.iouThreshold(), spec.maxDetections())
                : scratch;
        List<Detection> out = new ArrayList<>(Math.min(kept.size(), spec.maxDetections()));
        for (RawDetection r : kept) {
            if (!labelMap.isSupported(r.classIndex())) {
                continue; // policy: unsupported model class → ignored
            }
            ObjectClass cls = labelMap.classFor(r.classIndex());
            BoundingBox box = lb.toSource(r.x1(), r.y1(), r.x2(), r.y2());
            if (box == null) {
                continue; // degenerate / non-finite after inverse transform
            }
            float conf = r.confidence();
            if (Float.isNaN(conf) || conf < 0f || conf > 1f) {
                continue;
            }
            out.add(new Detection(r.classIndex(), cls, conf, box, frameTs));
            if (out.size() >= spec.maxDetections()) {
                break;
            }
        }
        return out;
    }

    private DetectionException failure(DetectionException e) {
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            fail("repeated inference failures (" + consecutiveFailures + "): " + e.getMessage());
        }
        return e;
    }

    private String inputName() {
        if (inputName == null) inputName = spec.inputName() != null ? spec.inputName() : session.inputs().get(0).name();
        return inputName;
    }

    private String outputName() {
        if (outputName == null) outputName = spec.outputName() != null ? spec.outputName() : session.outputs().get(0).name();
        return outputName;
    }

    // ---------------------------------------------------------------- misc

    @Override
    public DetectorState state() {
        return state;
    }

    public String stateDetail() {
        return stateDetail;
    }

    @Override
    public LabelMap labelMap() {
        return labelMap;
    }

    @Override
    public DetectorInfo info() {
        ModelSpec s = spec;
        if (s == null) {
            return new DetectorInfo(modelDir, "unknown", "unknown", 0, 0, "unknown", false, "NONE", "");
        }
        return new DetectorInfo(s.modelId(), s.family(), s.version(), s.inputWidth(), s.inputHeight(), s.decoder().name(),
                s.nmsInModel(), session != null ? session.executionProvider() : "NONE",
                labelMap == null ? "" : labelMap.supportedClasses().toString());
    }

    @Override
    public DetectorTimings lastTimings() {
        return lastTimings;
    }

    public ModelSpec spec() {
        return spec;
    }

    @Override
    public synchronized void close() {
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException e) {
                ZLog.w(TAG, "session close: " + e);
            }
            session = null;
        }
        setState(DetectorState.CLOSED, "");
    }

    private void fail(String detail) {
        setState(DetectorState.ERROR, detail == null ? "" : detail);
        ZLog.w(TAG, "detector ERROR: " + detail);
    }

    private void setState(DetectorState s, String detail) {
        state = s;
        stateDetail = detail;
    }

    private String readAll(String path) throws IOException {
        try (InputStream in = files.open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(InputStream in) throws IOException {
        try (in) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String join(String dir, String file) {
        return dir.endsWith("/") ? dir + file : dir + "/" + file;
    }
}
