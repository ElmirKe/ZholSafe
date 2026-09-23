package kz.zholsafe.ai.preprocess;

import kz.zholsafe.model.BoundingBox;

/**
 * Aspect-preserving resize of an upright W×H image into an M_w×M_h model input with centred
 * padding, and the exact inverse for boxes.
 *
 * <pre>
 * scale = min(Mw / W, Mh / H)
 * newW = round(W * scale), newH = round(H * scale)
 * padX = (Mw - newW) / 2, padY = (Mh - newH) / 2      (integer pixels, left/top)
 * </pre>
 * Inverse: {@code src = (model - pad) / scale}, then clamp to {@code [0,W]×[0,H]}.
 * Never multiplies x and y by independent factors when letterboxing is active.
 */
public record LetterboxTransform(
        int srcWidth, int srcHeight, int dstWidth, int dstHeight,
        float scale, int newWidth, int newHeight, int padX, int padY) {

    public static LetterboxTransform compute(int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        float scale = Math.min((float) dstWidth / srcWidth, (float) dstHeight / srcHeight);
        int newW = Math.max(1, Math.round(srcWidth * scale));
        int newH = Math.max(1, Math.round(srcHeight * scale));
        int padX = (dstWidth - newW) / 2;
        int padY = (dstHeight - newH) / 2;
        return new LetterboxTransform(srcWidth, srcHeight, dstWidth, dstHeight, scale, newW, newH, padX, padY);
    }

    /** Plain stretch (no letterbox): independent x/y scales, zero padding. Only for letterbox=false specs. */
    public static LetterboxTransform stretch(int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        return new LetterboxTransform(srcWidth, srcHeight, dstWidth, dstHeight, Float.NaN, dstWidth, dstHeight, 0, 0);
    }

    public boolean isStretch() {
        return Float.isNaN(scale);
    }

    /**
     * Maps a model-space box (pixels in the dst image) back to upright source pixels, clamped.
     *
     * @return the source box, or {@code null} if it is degenerate (zero/negative area after clamp)
     *         or any coordinate is non-finite.
     */
    public BoundingBox toSource(float mx1, float my1, float mx2, float my2) {
        if (!finite(mx1) || !finite(my1) || !finite(mx2) || !finite(my2)) {
            return null;
        }
        float sx1;
        float sy1;
        float sx2;
        float sy2;
        if (isStretch()) {
            float fx = (float) srcWidth / dstWidth;
            float fy = (float) srcHeight / dstHeight;
            sx1 = mx1 * fx; sx2 = mx2 * fx; sy1 = my1 * fy; sy2 = my2 * fy;
        } else {
            sx1 = (mx1 - padX) / scale;
            sx2 = (mx2 - padX) / scale;
            sy1 = (my1 - padY) / scale;
            sy2 = (my2 - padY) / scale;
        }
        float x1 = clamp(Math.min(sx1, sx2), 0, srcWidth);
        float x2 = clamp(Math.max(sx1, sx2), 0, srcWidth);
        float y1 = clamp(Math.min(sy1, sy2), 0, srcHeight);
        float y2 = clamp(Math.max(sy1, sy2), 0, srcHeight);
        if (x2 - x1 <= 0f || y2 - y1 <= 0f) {
            return null;
        }
        return new BoundingBox(x1, y1, x2, y2);
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
