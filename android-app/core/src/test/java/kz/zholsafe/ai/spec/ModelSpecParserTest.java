package kz.zholsafe.ai.spec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSpecParserTest {

    static final String YOLO11 = """
            {
              "modelId": "zholsafe-yolo11n-coco",
              "family": "yolo11", "version": "8.3.0-pretrained-coco",
              "modelFile": "model.onnx", "labelsFile": "labels.txt",
              "inputName": "images", "outputName": "output0",
              "inputWidth": 640, "inputHeight": 640, "inputChannels": 3,
              "layout": "NCHW", "inputType": "FLOAT32", "normalization": "SCALE_0_1",
              "letterbox": true, "padValue": 114,
              "confidenceThreshold": 0.35, "iouThreshold": 0.45,
              "decoder": "YOLO_RAW_CXCYWH_NC", "nmsInModel": false,
              "numClasses": 80, "maxDetections": 50,
              "labelAliases": {"cattle": "cow"},
              "sha256": null,
              "metadata": {"opset": 17, "precision": "FP32", "ignored": [1,2,3]}
            }
            """;

    @Test
    void parsesFullSpec() {
        ModelSpec s = ModelSpecParser.parse(YOLO11);
        assertEquals("zholsafe-yolo11n-coco", s.modelId());
        assertEquals("images", s.inputName());
        assertEquals(640, s.inputWidth());
        assertEquals(ModelSpec.DecoderType.YOLO_RAW_CXCYWH_NC, s.decoder());
        assertEquals(0.35f, s.confidenceThreshold(), 1e-6);
        assertEquals(80, s.numClasses());
        assertEquals("cow", s.labelAliases().get("cattle"));
        assertNull(s.sha256());
        assertEquals(java.util.List.of(1L, 3L, 640L, 640L), s.inputShape());
    }

    @Test
    void missingRequiredKeyFails() {
        String bad = YOLO11.replace("\"decoder\": \"YOLO_RAW_CXCYWH_NC\",", "");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ModelSpecParser.parse(bad));
        assertTrue(e.getMessage().contains("decoder"));
    }

    @Test
    void inconsistentNmsFlagFails() {
        String bad = YOLO11.replace("\"nmsInModel\": false", "\"nmsInModel\": true");
        assertThrows(IllegalArgumentException.class, () -> ModelSpecParser.parse(bad));
    }

    @Test
    void malformedJsonFails() {
        assertThrows(IllegalArgumentException.class, () -> ModelSpecParser.parse("{ \"modelId\": "));
    }
}
