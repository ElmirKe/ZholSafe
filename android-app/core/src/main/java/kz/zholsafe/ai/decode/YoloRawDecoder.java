package kz.zholsafe.ai.decode;

import kz.zholsafe.ai.spec.ModelSpec;

import java.util.List;

/**
 * Ultralytics raw detect export ({@code nms=False}): output {@code [1, 4+nc, N]}, row-major,
 * i.e. attribute-major: row 0..3 = cx, cy, w, h (model-input pixels), rows 4..4+nc-1 = per-class
 * scores already passed through sigmoid (no separate objectness in v8/11 heads).
 *
 * <p><b>Confidence rule:</b> confidence = max over class scores; class = argmax. Candidates below
 * {@code spec.confidenceThreshold()} are dropped here. NMS is REQUIRED afterwards.
 *
 * <p><b>Trust boundary:</b> every value that reaches a {@link RawDetection} must be finite.
 * NaN/±Infinity in any class score, centre, width or height rejects that candidate (a NaN score
 * never wins the argmax; a non-finite geometry is discarded). Downstream clamping is not relied on.
 */
public final class YoloRawDecoder implements DetectionDecoder {

    @Override
    public ModelSpec.DecoderType type() {
        return ModelSpec.DecoderType.YOLO_RAW_CXCYWH_NC;
    }

    @Override
    public boolean requiresNms() {
        return true;
    }

    @Override
    public void validateShape(long[] shape, ModelSpec spec) throws IncompatibleOutputException {
        String expected = "[1, " + (4 + spec.numClasses()) + ", N]";
        if (shape == null || shape.length != 3) {
            throw IncompatibleOutputException.shape(expected + " rank 3", shape);
        }
        if (shape[0] != 1 && shape[0] != -1) {
            throw IncompatibleOutputException.shape(expected + " batch 1", shape);
        }
        if (shape[1] != 4 + spec.numClasses()) {
            if (shape[2] == 4 + spec.numClasses()) {
                throw new IncompatibleOutputException("output looks transposed [1, N, 4+nc]; this decoder expects "
                        + expected + ", received " + java.util.Arrays.toString(shape));
            }
            throw IncompatibleOutputException.shape(expected + " (class dimension " + (4 + spec.numClasses()) + ")", shape);
        }
    }

    @Override
    public void decode(float[] data, long[] shape, ModelSpec spec, List<RawDetection> out) throws IncompatibleOutputException {
        validateShape(shape, spec);
        int attrs = (int) shape[1];
        int n = (int) shape[2];
        if (n <= 0 || data.length < attrs * n) {
            throw new IncompatibleOutputException("output buffer length " + data.length + " < " + attrs + "*" + n);
        }
        int nc = spec.numClasses();
        float thr = spec.confidenceThreshold();
        for (int i = 0; i < n; i++) {
            float best = -1f;
            int bestIdx = -1;
            for (int c = 0; c < nc; c++) {
                float s = data[(4 + c) * n + i];
                if (Decoders.isFinite(s) && s > best) { // NaN/±Inf scores can never be selected
                    best = s;
                    bestIdx = c;
                }
            }
            if (bestIdx < 0 || best < thr || best > 1f) {
                continue; // no finite score, below threshold, or out of [0,1] → not a detection
            }
            float cx = data[i];
            float cy = data[n + i];
            float w = data[2 * n + i];
            float h = data[3 * n + i];
            if (!Decoders.allFinite(cx, cy, w, h) || w <= 0f || h <= 0f) {
                continue; // malformed geometry is rejected here, not left for clamping
            }
            out.add(new RawDetection(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, best, bestIdx));
        }
    }
}
