package kz.zholsafe.ai;

import kz.zholsafe.ai.infer.ModelFiles;
import kz.zholsafe.ai.infer.TensorElementType;
import kz.zholsafe.ai.infer.TensorSession;
import kz.zholsafe.ai.infer.TensorSessionFactory;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the full detector path with an in-memory model directory and a scripted session. */
class OnnxRoadDetectorTest {

    static final String SPEC_RAW = """
            {"modelId":"t-raw","family":"yolo11","version":"t","modelFile":"model.onnx","labelsFile":"labels.txt",
             "inputWidth":8,"inputHeight":8,"layout":"NCHW","inputType":"FLOAT32","normalization":"SCALE_0_1",
             "letterbox":true,"confidenceThreshold":0.5,"iouThreshold":0.5,"decoder":"YOLO_RAW_CXCYWH_NC",
             "nmsInModel":false,"numClasses":3,"maxDetections":10}
            """;

    /** In-memory files. */
    static final class MemFiles implements ModelFiles {
        final Map<String, byte[]> m = new HashMap<>();
        MemFiles put(String p, String s) { m.put(p, s.getBytes(StandardCharsets.UTF_8)); return this; }
        @Override public boolean exists(String p) { return m.containsKey(p); }
        @Override public InputStream open(String p) throws IOException {
            if (!m.containsKey(p)) throw new IOException("no " + p);
            return new ByteArrayInputStream(m.get(p));
        }
        @Override public String absolutePath(String p) throws ModelNotAvailableException {
            if (!m.containsKey(p)) throw new ModelNotAvailableException(p, "missing");
            return "/mem/" + p;
        }
    }

    /** Scripted session: fixed I/O metadata, output produced by a function of the input tensor. */
    static final class FakeSession implements TensorSession {
        final long[] inShape;
        final long[] outShape;
        final Function<float[], Result> fn;
        TensorElementType inType = TensorElementType.FLOAT32;
        TensorElementType outType = TensorElementType.FLOAT32;
        boolean closed;
        float[] lastInput;
        FakeSession(long[] inShape, long[] outShape, Function<float[], Result> fn) {
            this.inShape = inShape; this.outShape = outShape; this.fn = fn;
        }
        @Override public List<TensorInfo> inputs() { return List.of(new TensorInfo("images", inShape, inType)); }
        @Override public List<TensorInfo> outputs() { return List.of(new TensorInfo("output0", outShape, outType)); }
        @Override public String executionProvider() { return "FAKE"; }
        @Override public Result run(String in, float[] input, long[] shape, String out) { lastInput = input.clone(); return fn.apply(input); }
        @Override public void close() { closed = true; }
    }

    static TensorSessionFactory factory(TensorSession s) {
        return new TensorSessionFactory() {
            @Override public TensorSession open(String p) { return s; }
            @Override public String requestedExecutionProvider() { return "FAKE"; }
        };
    }

    static MemFiles files(String spec, String labels) {
        return new MemFiles().put("m/model-spec.json", spec).put("m/labels.txt", labels).put("m/model.onnx", "not-a-real-model");
    }

    /** 8×8 model output [1, 7, 2]: two candidates. */
    static float[] rawOut(float[][] rows) {
        int n = rows.length;
        float[] d = new float[7 * n];
        for (int i = 0; i < n; i++) for (int a = 0; a < 7; a++) d[a * n + i] = rows[i][a];
        return d;
    }

    static Frame frame(int w, int h, int rot) {
        ByteBuffer b = ByteBuffer.allocate(Frame.packedSize(Frame.PixelFormat.NV21, w, h));
        for (int i = w * h; i < b.capacity(); i++) b.put(i, (byte) 128);
        return new Frame(w, h, Frame.PixelFormat.NV21, b, rot, 777L, Frame.CameraSource.ROAD);
    }

    @Test
    void endToEndRawModelWithLabelMapNmsAndLetterboxInverse() throws Exception {
        // labels: index0=person, index1=car (unsupported), index2=cow
        MemFiles f = files(SPEC_RAW, "person\ncar\ncow\n");
        // model space 8×8; frame 16×8 upright → scale 0.5, padY = 2
        FakeSession s = new FakeSession(new long[] { 1, 3, 8, 8 }, new long[] { 1, 7, -1 }, in -> new TensorSession.Result(
                rawOut(new float[][] {
                        { 4, 4, 4, 2, 0.9f, 0f, 0f },   // person, model box (2,3)-(6,5) → src (4,2)-(12,6)
                        { 4, 4, 4, 2, 0.8f, 0f, 0f },   // duplicate person → NMS suppressed
                        { 2, 4, 2, 2, 0f, 0.95f, 0f },  // car → unsupported → ignored
                        { 6, 4, 2, 2, 0f, 0f, 0.7f },   // cow
                        { 1, 1, 1, 1, 0f, 0f, 0.99f } }), // cow entirely in top padding → degenerate → dropped
                new long[] { 1, 7, 5 }));
        OnnxRoadDetector d = new OnnxRoadDetector("m", f, factory(s));
        d.load();
        assertEquals(DetectorState.READY, d.state());
        assertEquals("[PERSON, COW]", d.info().supportedClasses());
        List<Detection> out = d.detect(frame(16, 8, 0));
        assertEquals(2, out.size());
        Detection person = out.get(0);
        assertEquals(ObjectClass.PERSON, person.objectClass());
        assertEquals(0, person.classId());
        assertEquals(4f, person.box().x1(), 1e-4); assertEquals(2f, person.box().y1(), 1e-4);
        assertEquals(12f, person.box().x2(), 1e-4); assertEquals(6f, person.box().y2(), 1e-4);
        assertEquals(777L, person.timestampNanos());
        assertEquals(ObjectClass.COW, out.get(1).objectClass());
        assertEquals(2, out.get(1).classId(), "classId stays the MODEL index");
        assertTrue(d.lastTimings().totalNanos() >= 0);
        d.close();
        assertTrue(s.closed);
        assertEquals(DetectorState.CLOSED, d.state());
    }

    @Test
    void rotatedFrameBoxesAreInUprightCoordinates() throws Exception {
        MemFiles f = files(SPEC_RAW, "person\ncar\ncow\n");
        // stored 8×16 rotated 90 → upright 16×8 (same as previous test) → same expected box
        FakeSession s = new FakeSession(new long[] { 1, 3, 8, 8 }, new long[] { 1, 7, -1 }, in -> new TensorSession.Result(
                rawOut(new float[][] { { 4, 4, 4, 2, 0.9f, 0f, 0f } }), new long[] { 1, 7, 1 }));
        OnnxRoadDetector d = new OnnxRoadDetector("m", f, factory(s));
        d.load();
        Frame fr = frame(8, 16, 90);
        assertEquals(16, fr.uprightWidth());
        List<Detection> out = d.detect(fr);
        assertEquals(1, out.size());
        assertEquals(4f, out.get(0).box().x1(), 1e-4);
        assertEquals(12f, out.get(0).box().x2(), 1e-4);
        assertTrue(out.get(0).box().x2() <= fr.uprightWidth() && out.get(0).box().y2() <= fr.uprightHeight());
    }

    @Test
    void missingModelFileFailsFastWithoutFakeDetections() {
        MemFiles f = new MemFiles().put("m/model-spec.json", SPEC_RAW).put("m/labels.txt", "person\ncar\ncow\n");
        OnnxRoadDetector d = new OnnxRoadDetector("m", f, factory(null));
        ModelNotAvailableException e = assertThrows(ModelNotAvailableException.class, d::load);
        assertTrue(e.getMessage().contains("ONNX file missing"));
        assertEquals(DetectorState.ERROR, d.state());
        assertThrows(DetectionException.class, () -> d.detect(frame(8, 8, 0)));
    }

    @Test
    void missingSpecAndLabelCountMismatchFail() {
        OnnxRoadDetector noSpec = new OnnxRoadDetector("m", new MemFiles(), factory(null));
        assertTrue(assertThrows(ModelNotAvailableException.class, noSpec::load).getMessage().contains("model-spec.json"));
        OnnxRoadDetector badLabels = new OnnxRoadDetector("m", files(SPEC_RAW, "person\ncow\n"), factory(null));
        assertTrue(assertThrows(ModelNotAvailableException.class, badLabels::load).getMessage().contains("numClasses"));
        OnnxRoadDetector noCanonical = new OnnxRoadDetector("m", files(SPEC_RAW, "car\nbus\ntruck\n"), factory(null));
        assertTrue(assertThrows(ModelNotAvailableException.class, noCanonical::load).getMessage().contains("canonical"));
    }

    @Test
    void incompatibleSessionShapesFailAtLoad() {
        MemFiles f = files(SPEC_RAW, "person\ncar\ncow\n");
        // wrong input size
        FakeSession badIn = new FakeSession(new long[] { 1, 3, 640, 640 }, new long[] { 1, 7, 100 }, in -> null);
        assertTrue(assertThrows(ModelNotAvailableException.class,
                () -> new OnnxRoadDetector("m", f, factory(badIn)).load()).getMessage().contains("input shape"));
        // wrong class dimension (80-class model with 3-class spec)
        FakeSession badOut = new FakeSession(new long[] { 1, 3, 8, 8 }, new long[] { 1, 84, 8400 }, in -> null);
        String msg = assertThrows(ModelNotAvailableException.class,
                () -> new OnnxRoadDetector("m", f, factory(badOut)).load()).getMessage();
        assertTrue(msg.contains("MODEL_INCOMPATIBLE"), msg);
        // end2end tensor where RAW expected
        FakeSession e2e = new FakeSession(new long[] { 1, 3, 8, 8 }, new long[] { 1, 300, 6 }, in -> null);
        assertThrows(ModelNotAvailableException.class, () -> new OnnxRoadDetector("m", f, factory(e2e)).load());
    }

    @Test
    void nonFloat32TensorsAreRejectedAtLoadWithCanonicalTypeDiagnostic() {
        MemFiles f = files(SPEC_RAW, "person\ncar\ncow\n");
        FakeSession fp16In = new FakeSession(new long[] { 1, 3, 8, 8 }, new long[] { 1, 7, 100 }, in -> null);
        fp16In.inType = TensorElementType.FLOAT16;
        String m1 = assertThrows(ModelNotAvailableException.class,
                () -> new OnnxRoadDetector("m", f, factory(fp16In)).load()).getMessage();
        assertTrue(m1.contains("FLOAT16") && m1.contains("FLOAT32"), m1);
        FakeSession int8Out = new FakeSession(new long[] { 1, 3, 8, 8 }, new long[] { 1, 7, 100 }, in -> null);
        int8Out.outType = TensorElementType.INT8;
        String m2 = assertThrows(ModelNotAvailableException.class,
                () -> new OnnxRoadDetector("m", f, factory(int8Out)).load()).getMessage();
        assertTrue(m2.contains("INT8"), m2);
        FakeSession unknown = new FakeSession(new long[] { 1, 3, 8, 8 }, new long[] { 1, 7, 100 }, in -> null);
        unknown.outType = TensorElementType.UNKNOWN;
        assertThrows(ModelNotAvailableException.class, () -> new OnnxRoadDetector("m", f, factory(unknown)).load());
    }

    @Test
    void sha256MismatchIsRejected() {
        String spec = SPEC_RAW.replace("\"maxDetections\":10", "\"maxDetections\":10,\"sha256\":\"deadbeef\"");
        OnnxRoadDetector d = new OnnxRoadDetector("m", files(spec, "person\ncar\ncow\n"), factory(null));
        assertTrue(assertThrows(ModelNotAvailableException.class, d::load).getMessage().contains("sha256 mismatch"));
    }

    @Test
    void runtimeShapeMismatchOnFirstInferenceIsFatal() throws Exception {
        MemFiles f = files(SPEC_RAW, "person\ncar\ncow\n");
        // static metadata passes (dynamic N) but the concrete runtime output is an end2end tensor
        FakeSession s = new FakeSession(new long[] { 1, 3, 8, 8 }, new long[] { 1, 7, -1 },
                in -> new TensorSession.Result(new float[6], new long[] { 1, 1, 6 }));
        OnnxRoadDetector d = new OnnxRoadDetector("m", f, factory(s));
        d.load();
        assertThrows(DetectionException.class, () -> d.detect(frame(8, 8, 0)));
        assertEquals(DetectorState.ERROR, d.state());
    }

    @Test
    void repeatedInferenceFailuresBecomeFatal() throws Exception {
        MemFiles f = files(SPEC_RAW, "person\ncar\ncow\n");
        FakeSession s = new FakeSession(new long[] { 1, 3, 8, 8 }, new long[] { 1, 7, 1 }, in -> { throw new IllegalStateException("ort boom"); });
        OnnxRoadDetector d = new OnnxRoadDetector("m", f, factory(s));
        d.load();
        for (int i = 0; i < OnnxRoadDetector.MAX_CONSECUTIVE_FAILURES - 1; i++) {
            assertThrows(DetectionException.class, () -> d.detect(frame(8, 8, 0)));
            assertEquals(DetectorState.READY, d.state(), "single failures do not kill the detector");
        }
        assertThrows(DetectionException.class, () -> d.detect(frame(8, 8, 0)));
        assertEquals(DetectorState.ERROR, d.state());
    }

    @Test
    void preprocessedTensorReachesSessionWithSpecShape() throws Exception {
        MemFiles f = files(SPEC_RAW, "person\ncar\ncow\n");
        FakeSession s = new FakeSession(new long[] { 1, 3, 8, 8 }, new long[] { 1, 7, 1 },
                in -> new TensorSession.Result(rawOut(new float[][] { { 4, 4, 4, 4, 0f, 0f, 0f } }), new long[] { 1, 7, 1 }));
        OnnxRoadDetector d = new OnnxRoadDetector("m", f, factory(s));
        d.load();
        assertTrue(d.detect(frame(8, 8, 0)).isEmpty(), "NO DETECTIONS is an empty list from a READY detector");
        assertEquals(3 * 8 * 8, s.lastInput.length);
    }
}
