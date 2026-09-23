package kz.zholsafe.ai;

import kz.zholsafe.ai.spec.ModelSpec;

import java.util.Map;

public final class TestSpecs {
    private TestSpecs() { }

    public static ModelSpec raw(int size, int numClasses, float conf, float iou) {
        return new ModelSpec("test-raw", "yolo11", "t", "model.onnx", "labels.txt", null, null, size, size, 3,
                ModelSpec.TensorLayout.NCHW, ModelSpec.InputType.FLOAT32, ModelSpec.Normalization.SCALE_0_1,
                true, 114, conf, iou, ModelSpec.DecoderType.YOLO_RAW_CXCYWH_NC, false, numClasses, 100, Map.of(), null);
    }

    public static ModelSpec end2end(int size, int numClasses, float conf) {
        return new ModelSpec("test-e2e", "yolo26", "t", "model.onnx", "labels.txt", null, null, size, size, 3,
                ModelSpec.TensorLayout.NCHW, ModelSpec.InputType.FLOAT32, ModelSpec.Normalization.SCALE_0_1,
                true, 114, conf, 0.5f, ModelSpec.DecoderType.YOLO_END2END_XYXY_CONF_CLS, true, numClasses, 100, Map.of(), null);
    }

    public static ModelSpec rawNoNorm(int w, int h, boolean letterbox, ModelSpec.TensorLayout layout) {
        return new ModelSpec("test-raw-nonorm", "yolo11", "t", "model.onnx", "labels.txt", null, null, w, h, 3,
                layout, ModelSpec.InputType.FLOAT32, ModelSpec.Normalization.NONE,
                letterbox, 114, 0.25f, 0.5f, ModelSpec.DecoderType.YOLO_RAW_CXCYWH_NC, false, 5, 100, Map.of(), null);
    }
}
