package kz.zholsafe.ai.decode;

/**
 * One decoded candidate in <b>model-input (letterboxed) pixel coordinates</b>, before label
 * mapping, letterbox inversion and NMS. {@code classIndex} is the MODEL index, not an ObjectClass.
 * All numeric fields are finite and {@code classIndex >= 0} — enforced by the constructor so no
 * decoder can leak malformed model output past this type.
 */
public record RawDetection(float x1, float y1, float x2, float y2, float confidence, int classIndex) {

    public RawDetection {
        if (!Decoders.allFinite(x1, y1, x2, y2) || !Decoders.isFinite(confidence)) {
            throw new IllegalArgumentException("RawDetection fields must be finite");
        }
        if (classIndex < 0) {
            throw new IllegalArgumentException("classIndex must be >= 0");
        }
    }
}
