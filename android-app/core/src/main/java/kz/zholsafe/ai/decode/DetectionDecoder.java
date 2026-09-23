package kz.zholsafe.ai.decode;

import kz.zholsafe.ai.spec.ModelSpec;

import java.util.List;

/**
 * Turns one raw output tensor into candidates. Each implementation owns exactly one output
 * contract ({@link ModelSpec.DecoderType}) and documents its confidence rule. Decoders must
 * validate {@code shape} and throw {@link IncompatibleOutputException} rather than guess.
 */
public interface DetectionDecoder {

    ModelSpec.DecoderType type();

    /**
     * Validates the output shape against the spec. Called once after session creation with the
     * static shape (may contain -1 for dynamic dims) and again with the concrete shape on the
     * first inference.
     */
    void validateShape(long[] shape, ModelSpec spec) throws IncompatibleOutputException;

    /**
     * @param data      flattened row-major output tensor
     * @param shape     concrete shape of {@code data}
     * @param spec      thresholds and class count
     * @param out       list to append candidates to (cleared by the caller)
     */
    void decode(float[] data, long[] shape, ModelSpec spec, List<RawDetection> out) throws IncompatibleOutputException;

    /** Whether the caller must run NMS after decoding. */
    boolean requiresNms();

    static DetectionDecoder forType(ModelSpec.DecoderType type) {
        switch (type) {
            case YOLO_RAW_CXCYWH_NC: return new YoloRawDecoder();
            case YOLO_END2END_XYXY_CONF_CLS: return new YoloEnd2EndDecoder();
            default: throw new IllegalArgumentException("no decoder for " + type);
        }
    }
}
