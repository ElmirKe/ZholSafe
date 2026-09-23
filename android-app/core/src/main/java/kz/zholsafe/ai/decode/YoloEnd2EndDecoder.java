package kz.zholsafe.ai.decode;

import kz.zholsafe.ai.spec.ModelSpec;

import java.util.List;

/**
 * Ultralytics end-to-end export (YOLO26 default head, YOLOv10, or YOLO11 exported with
 * {@code nms=True}): output {@code [1, K, 6]} with each row = x1, y1, x2, y2, confidence,
 * classIndex (float). Top-k / NMS already happened inside the graph; rows are padded with
 * low-confidence entries, so the confidence threshold still applies. NO NMS afterwards.
 *
 * <p><b>Confidence rule:</b> column 4 is the model's final score; used as-is.
 */
public final class YoloEnd2EndDecoder implements DetectionDecoder {

    @Override
    public ModelSpec.DecoderType type() {
        return ModelSpec.DecoderType.YOLO_END2END_XYXY_CONF_CLS;
    }

    @Override
    public boolean requiresNms() {
        return false;
    }

    @Override
    public void validateShape(long[] shape, ModelSpec spec) throws IncompatibleOutputException {
        String expected = "[1, K, 6]";
        if (shape == null || shape.length != 3) {
            throw IncompatibleOutputException.shape(expected + " rank 3", shape);
        }
        if (shape[0] != 1 && shape[0] != -1) {
            throw IncompatibleOutputException.shape(expected + " batch 1", shape);
        }
        if (shape[2] != 6) {
            if (shape[1] == 4 + spec.numClasses()) {
                throw new IncompatibleOutputException("output is a RAW [1, 4+nc, N] tensor; spec says end2end. "
                        + "Fix model-spec.json decoder=YOLO_RAW_CXCYWH_NC / nmsInModel=false. Received "
                        + java.util.Arrays.toString(shape));
            }
            throw IncompatibleOutputException.shape(expected + " (last dim 6)", shape);
        }
    }

    @Override
    public void decode(float[] data, long[] shape, ModelSpec spec, List<RawDetection> out) throws IncompatibleOutputException {
        validateShape(shape, spec);
        int k = (int) shape[1];
        if (data.length < k * 6) {
            throw new IncompatibleOutputException("output buffer length " + data.length + " < " + k + "*6");
        }
        float thr = spec.confidenceThreshold();
        for (int i = 0; i < k; i++) {
            int o = i * 6;
            float conf = data[o + 4];
            if (Float.isNaN(conf) || conf < thr || conf > 1f) {
                continue;
            }
            float cls = data[o + 5];
            if (Float.isNaN(cls) || cls < 0 || cls >= spec.numClasses()) {
                continue;
            }
            out.add(new RawDetection(data[o], data[o + 1], data[o + 2], data[o + 3], conf, (int) cls));
        }
    }
}
