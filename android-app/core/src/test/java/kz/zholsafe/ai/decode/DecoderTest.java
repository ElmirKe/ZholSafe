package kz.zholsafe.ai.decode;

import kz.zholsafe.ai.TestSpecs;
import kz.zholsafe.ai.spec.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecoderTest {

    static final int NC = 5;

    /** Builds a [1, 4+nc, n] attribute-major tensor from rows of {cx,cy,w,h, s0..s4}. */
    static float[] rawTensor(float[][] rows) {
        int n = rows.length;
        float[] d = new float[(4 + NC) * n];
        for (int i = 0; i < n; i++) for (int a = 0; a < 4 + NC; a++) d[a * n + i] = rows[i][a];
        return d;
    }

    static long[] rawShape(int n) {
        return new long[] { 1, 4 + NC, n };
    }

    @Test
    void rawDecoderZeroDetectionsWhenAllBelowThreshold() throws Exception {
        ModelSpec s = TestSpecs.raw(640, NC, 0.5f, 0.5f);
        List<RawDetection> out = new ArrayList<>();
        new YoloRawDecoder().decode(rawTensor(new float[][] { { 10, 10, 5, 5, 0.1f, 0.2f, 0.3f, 0.4f, 0.45f } }), rawShape(1), s, out);
        assertTrue(out.isEmpty());
    }

    @Test
    void rawDecoderOneDetectionArgmaxAndCxcywhToXyxy() throws Exception {
        ModelSpec s = TestSpecs.raw(640, NC, 0.5f, 0.5f);
        List<RawDetection> out = new ArrayList<>();
        new YoloRawDecoder().decode(rawTensor(new float[][] { { 100, 200, 40, 60, 0.1f, 0.9f, 0.3f, 0f, 0f } }), rawShape(1), s, out);
        assertEquals(1, out.size());
        RawDetection r = out.get(0);
        assertEquals(1, r.classIndex());
        assertEquals(0.9f, r.confidence());
        assertEquals(80f, r.x1()); assertEquals(170f, r.y1()); assertEquals(120f, r.x2()); assertEquals(230f, r.y2());
    }

    @Test
    void rawDecoderMultipleAndLowConfidenceFiltered() throws Exception {
        ModelSpec s = TestSpecs.raw(640, NC, 0.5f, 0.5f);
        List<RawDetection> out = new ArrayList<>();
        new YoloRawDecoder().decode(rawTensor(new float[][] {
                { 100, 100, 10, 10, 0.95f, 0, 0, 0, 0 },
                { 300, 300, 10, 10, 0, 0, 0, 0, 0.49f },   // filtered
                { 500, 500, 10, 10, 0, 0, 0.7f, 0, 0 } }), rawShape(3), s, out);
        assertEquals(2, out.size());
        assertEquals(0, out.get(0).classIndex());
        assertEquals(2, out.get(1).classIndex());
    }

    @Test
    void rawDecoderDropsNaNConfidenceAndNonPositiveSize() throws Exception {
        ModelSpec s = TestSpecs.raw(640, NC, 0.25f, 0.5f);
        List<RawDetection> out = new ArrayList<>();
        new YoloRawDecoder().decode(rawTensor(new float[][] {
                { 100, 100, 10, 10, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN },
                { 100, 100, 0, 10, 0.9f, 0, 0, 0, 0 },
                { 100, 100, Float.NaN, 10, 0.9f, 0, 0, 0, 0 },
                { 100, 100, 10, 10, 1.5f, 0, 0, 0, 0 } }), rawShape(4), s, out);
        assertTrue(out.isEmpty());
    }

    @Test
    void rawDecoderInvalidShapesFailFast() {
        ModelSpec s = TestSpecs.raw(640, NC, 0.25f, 0.5f);
        YoloRawDecoder d = new YoloRawDecoder();
        assertThrows(IncompatibleOutputException.class, () -> d.validateShape(new long[] { 1, 84, 8400 }, s)); // 80 classes ≠ 5
        assertThrows(IncompatibleOutputException.class, () -> d.validateShape(new long[] { 1, 8400, 9 }, s));  // transposed
        assertThrows(IncompatibleOutputException.class, () -> d.validateShape(new long[] { 1, 9 }, s));        // rank
        assertThrows(IncompatibleOutputException.class, () -> d.validateShape(new long[] { 1, 300, 6 }, s));   // end2end tensor
        IncompatibleOutputException e = assertThrows(IncompatibleOutputException.class,
                () -> d.validateShape(new long[] { 2, 9, 100 }, s));
        assertTrue(e.getMessage().startsWith("MODEL_INCOMPATIBLE"));
    }

    @Test
    void rawDecoderAcceptsDynamicBatchAndAnchors() throws Exception {
        new YoloRawDecoder().validateShape(new long[] { -1, 9, -1 }, TestSpecs.raw(640, NC, 0.25f, 0.5f));
    }

    @Test
    void rawDecoderOutOfRangeCoordinatesArePassedThroughForClampingLater() throws Exception {
        // decoder is model-space; clamping happens in LetterboxTransform.toSource (tested there)
        ModelSpec s = TestSpecs.raw(640, NC, 0.25f, 0.5f);
        List<RawDetection> out = new ArrayList<>();
        new YoloRawDecoder().decode(rawTensor(new float[][] { { -10, 700, 40, 40, 0.9f, 0, 0, 0, 0 } }), rawShape(1), s, out);
        assertEquals(1, out.size());
        assertEquals(-30f, out.get(0).x1());
    }

    // ---- end2end ----

    static float[] e2e(float[][] rows) {
        float[] d = new float[rows.length * 6];
        for (int i = 0; i < rows.length; i++) System.arraycopy(rows[i], 0, d, i * 6, 6);
        return d;
    }

    @Test
    void end2endDecoderReadsXyxyConfClass() throws Exception {
        ModelSpec s = TestSpecs.end2end(640, NC, 0.25f);
        List<RawDetection> out = new ArrayList<>();
        new YoloEnd2EndDecoder().decode(e2e(new float[][] {
                { 10, 20, 30, 40, 0.8f, 3 },
                { 0, 0, 5, 5, 0.1f, 0 },          // below threshold (padding row)
                { 0, 0, 5, 5, 0.9f, 7 },          // class out of range → dropped
                { 0, 0, 5, 5, Float.NaN, 1 } }),  // NaN → dropped
                new long[] { 1, 4, 6 }, s, out);
        assertEquals(1, out.size());
        assertEquals(3, out.get(0).classIndex());
        assertEquals(new RawDetection(10, 20, 30, 40, 0.8f, 3), out.get(0));
    }

    @Test
    void end2endDecoderRejectsRawTensorWithHelpfulMessage() {
        ModelSpec s = TestSpecs.end2end(640, NC, 0.25f);
        IncompatibleOutputException e = assertThrows(IncompatibleOutputException.class,
                () -> new YoloEnd2EndDecoder().validateShape(new long[] { 1, 9, 8400 }, s));
        assertTrue(e.getMessage().contains("RAW"));
        assertThrows(IncompatibleOutputException.class, () -> new YoloEnd2EndDecoder().validateShape(new long[] { 1, 300 }, s));
    }

    @Test
    void decoderFactoryAndNmsFlags() {
        assertTrue(DetectionDecoder.forType(ModelSpec.DecoderType.YOLO_RAW_CXCYWH_NC).requiresNms());
        assertTrue(!DetectionDecoder.forType(ModelSpec.DecoderType.YOLO_END2END_XYXY_CONF_CLS).requiresNms());
    }

    @Test
    void specRejectsInconsistentNmsFlags() {
        assertThrows(IllegalArgumentException.class, () -> new ModelSpec("x", "f", "v", "m", "l", null, null, 640, 640, 3,
                ModelSpec.TensorLayout.NCHW, ModelSpec.InputType.FLOAT32, ModelSpec.Normalization.SCALE_0_1, true, 114,
                0.3f, 0.5f, ModelSpec.DecoderType.YOLO_RAW_CXCYWH_NC, true, 5, 100, null, null));
    }
}
