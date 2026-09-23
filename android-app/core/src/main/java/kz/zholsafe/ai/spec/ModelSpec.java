package kz.zholsafe.ai.spec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import kz.zholsafe.model.Contracts;

/**
 * Complete, explicit description of one ONNX detector export. Everything an
 * {@code OnnxRoadDetector} needs to know about a model lives here — nothing is inferred from the
 * model id, and nothing model-specific is hard-coded in the detector.
 *
 * <p>Loaded from {@code model-spec.json} next to the model (see {@link ModelSpecParser}) or built
 * in code for tests. Validated on construction.
 *
 * @param modelId            unique id, e.g. "zholsafe-yolo11n-coco"
 * @param family             "yolo11", "yolo26", ... (informational; the decoder is chosen by {@code decoder})
 * @param version            free-form
 * @param modelFile          ONNX file name relative to the model directory
 * @param labelsFile         labels file (one label per line, index = model class index)
 * @param inputName          input tensor name, or null → use the session's single input
 * @param outputName         output tensor name, or null → use the session's first output
 * @param inputWidth         model input width
 * @param inputHeight        model input height
 * @param inputChannels      3 for RGB
 * @param layout             NCHW or NHWC
 * @param inputType          FLOAT32 (only supported value in Stage 2)
 * @param normalization      how 0..255 RGB bytes become floats
 * @param letterbox          true → aspect-preserving resize + centred padding; false → plain stretch
 * @param padValue           letterbox fill value in 0..255 (YOLO default 114)
 * @param confidenceThreshold minimum final confidence [0,1]
 * @param iouThreshold       NMS IoU threshold [0,1] (ignored if {@code nmsInModel})
 * @param decoder            output decoder type
 * @param nmsInModel         true if the export already performs NMS/top-k (no NMS in app)
 * @param numClasses         expected class count (must equal labels file length)
 * @param maxDetections      cap after NMS
 * @param labelAliases       optional model-label → canonical-label aliases (label strings only)
 * @param sha256             optional hex digest of the ONNX file; verified at load if present
 */
public record ModelSpec(
        String modelId,
        String family,
        String version,
        String modelFile,
        String labelsFile,
        String inputName,
        String outputName,
        int inputWidth,
        int inputHeight,
        int inputChannels,
        TensorLayout layout,
        InputType inputType,
        Normalization normalization,
        boolean letterbox,
        int padValue,
        float confidenceThreshold,
        float iouThreshold,
        DecoderType decoder,
        boolean nmsInModel,
        int numClasses,
        int maxDetections,
        Map<String, String> labelAliases,
        String sha256) {

    public enum TensorLayout { NCHW, NHWC }

    public enum InputType { FLOAT32 }

    /** RGB byte → float rule. */
    public enum Normalization {
        /** x / 255 (Ultralytics default). */
        SCALE_0_1,
        /** raw 0..255 as float. */
        NONE
    }

    /**
     * Output tensor contracts. Only add a value when a real export needs different decoding.
     * <ul>
     *   <li>{@link #YOLO_RAW_CXCYWH_NC}: {@code [1, 4+nc, N]} rows = cx,cy,w,h then nc class scores
     *       (already sigmoid). Confidence = max class score. NMS required. This is the Ultralytics
     *       YOLOv8/YOLO11 {@code nms=False} ONNX export.</li>
     *   <li>{@link #YOLO_END2END_XYXY_CONF_CLS}: {@code [1, K, 6]} rows = x1,y1,x2,y2,conf,classIdx.
     *       NMS-free/top-k already applied in the graph. This is the Ultralytics end2end export
     *       (YOLO26 default, YOLOv10, YOLO11 with {@code nms=True}).</li>
     * </ul>
     * Coordinates in both are pixels in model-input (letterboxed) space.
     */
    public enum DecoderType { YOLO_RAW_CXCYWH_NC, YOLO_END2END_XYXY_CONF_CLS }

    public ModelSpec {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(modelFile, "modelFile");
        Objects.requireNonNull(labelsFile, "labelsFile");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(inputType, "inputType");
        Objects.requireNonNull(normalization, "normalization");
        Objects.requireNonNull(decoder, "decoder");
        labelAliases = labelAliases == null ? Map.of() : Map.copyOf(labelAliases);
        Contracts.positive("inputWidth", inputWidth);
        Contracts.positive("inputHeight", inputHeight);
        if (inputChannels != 3) {
            throw new IllegalArgumentException("inputChannels must be 3 (RGB), got " + inputChannels);
        }
        if (padValue < 0 || padValue > 255) {
            throw new IllegalArgumentException("padValue must be 0..255");
        }
        Contracts.unit("confidenceThreshold", confidenceThreshold);
        Contracts.unit("iouThreshold", iouThreshold);
        Contracts.positive("numClasses", numClasses);
        Contracts.positive("maxDetections", maxDetections);
        if (decoder == DecoderType.YOLO_END2END_XYXY_CONF_CLS && !nmsInModel) {
            throw new IllegalArgumentException("YOLO_END2END decoder implies nmsInModel=true");
        }
        if (decoder == DecoderType.YOLO_RAW_CXCYWH_NC && nmsInModel) {
            throw new IllegalArgumentException("YOLO_RAW decoder implies nmsInModel=false (NMS is applied in app)");
        }
    }

    public int inputTensorLength() {
        return inputWidth * inputHeight * inputChannels;
    }

    public List<Long> inputShape() {
        return layout == TensorLayout.NCHW
                ? List.of(1L, (long) inputChannels, (long) inputHeight, (long) inputWidth)
                : List.of(1L, (long) inputHeight, (long) inputWidth, (long) inputChannels);
    }
}
