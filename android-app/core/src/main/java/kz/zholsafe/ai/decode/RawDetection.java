package kz.zholsafe.ai.decode;

/**
 * One decoded candidate in <b>model-input (letterboxed) pixel coordinates</b>, before label
 * mapping, letterbox inversion and NMS. {@code classIndex} is the MODEL index, not an ObjectClass.
 */
public record RawDetection(float x1, float y1, float x2, float y2, float confidence, int classIndex) { }
