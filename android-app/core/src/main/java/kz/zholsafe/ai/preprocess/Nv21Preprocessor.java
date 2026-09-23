package kz.zholsafe.ai.preprocess;

import kz.zholsafe.ai.Frame;
import kz.zholsafe.ai.spec.ModelSpec;

import java.nio.ByteBuffer;

/**
 * Deterministic {@code Frame(NV21) → float tensor} conversion:
 * <pre>
 * NV21 → (rotate by frame.rotationDegrees, logically) → upright RGB
 *      → letterbox resize (nearest-neighbour) → normalise → NCHW/NHWC float[]
 * </pre>
 *
 * <p>The rotation is applied <em>logically</em>: for every destination pixel we compute the
 * upright coordinate, then map that to the stored (unrotated) buffer coordinate. No intermediate
 * rotated or RGB image is allocated; the only per-instance buffer is the reusable output tensor.
 * {@link #lastTransform()} describes the letterbox applied to the <b>upright</b> image, so box
 * inversion yields upright source pixels (the {@link kz.zholsafe.ai.RoadDetector} convention).
 *
 * <p>Rotation semantics: {@code rotationDegrees} is the clockwise rotation that makes the stored
 * buffer upright (CameraX {@code ImageInfo.getRotationDegrees()}). For a stored buffer of size
 * W×H and upright pixel (ux, uy):
 * <pre>
 *   0   : (x, y) = (ux, uy)
 *   90  : (x, y) = (uy, H' - 1 - ux)        where upright size is H×W, H' = stored H ... see code
 *   180 : (x, y) = (W - 1 - ux, H - 1 - uy)
 *   270 : (x, y) = (W' - 1 - uy, ux)
 * </pre>
 * Verified pixel-by-pixel in {@code Nv21PreprocessorTest}.
 *
 * <p>Not thread-safe: one instance per detector, used only on the processing thread.
 */
public final class Nv21Preprocessor {

    private final ModelSpec spec;
    private final float[] tensor;
    private LetterboxTransform lastTransform;

    public Nv21Preprocessor(ModelSpec spec) {
        this.spec = spec;
        this.tensor = new float[spec.inputTensorLength()];
    }

    /** The reusable output tensor; valid until the next {@link #process} call. */
    public float[] tensor() {
        return tensor;
    }

    public LetterboxTransform lastTransform() {
        return lastTransform;
    }

    /**
     * Fills {@link #tensor()} from the frame. Supports NV21 and (as a convenience for tests)
     * RGB_888. Returns the letterbox transform relative to the upright image.
     */
    public LetterboxTransform process(Frame frame) {
        int uw = frame.uprightWidth();
        int uh = frame.uprightHeight();
        int mw = spec.inputWidth();
        int mh = spec.inputHeight();
        LetterboxTransform t = spec.letterbox()
                ? LetterboxTransform.compute(uw, uh, mw, mh)
                : LetterboxTransform.stretch(uw, uh, mw, mh);
        lastTransform = t;

        float pad = normalise(spec.padValue());
        boolean nchw = spec.layout() == ModelSpec.TensorLayout.NCHW;
        int plane = mw * mh;
        ByteBuffer data = frame.data();
        int sw = frame.width();
        int sh = frame.height();
        int rot = frame.rotationDegrees();
        boolean nv21 = frame.format() == Frame.PixelFormat.NV21;
        if (!nv21 && frame.format() != Frame.PixelFormat.RGB_888) {
            throw new IllegalArgumentException("unsupported frame format " + frame.format());
        }
        int uvOffset = sw * sh;
        int uvRowStride = ((sw + 1) / 2) * 2;

        // Scale factors from dst pixel → upright source pixel (nearest neighbour, pixel-centre).
        float invScaleX = t.isStretch() ? (float) uw / t.newWidth() : 1f / t.scale();
        float invScaleY = t.isStretch() ? (float) uh / t.newHeight() : 1f / t.scale();

        for (int dy = 0; dy < mh; dy++) {
            int ry = dy - t.padY();
            boolean rowInside = ry >= 0 && ry < t.newHeight();
            int uy = rowInside ? clamp((int) ((ry + 0.5f) * invScaleY), 0, uh - 1) : 0;
            for (int dx = 0; dx < mw; dx++) {
                int rx = dx - t.padX();
                float r;
                float g;
                float b;
                if (rowInside && rx >= 0 && rx < t.newWidth()) {
                    int ux = clamp((int) ((rx + 0.5f) * invScaleX), 0, uw - 1);
                    // upright → stored buffer coordinates
                    int x;
                    int y;
                    switch (rot) {
                        case 90: x = uy; y = sh - 1 - ux; break;
                        case 180: x = sw - 1 - ux; y = sh - 1 - uy; break;
                        case 270: x = sw - 1 - uy; y = ux; break;
                        default: x = ux; y = uy;
                    }
                    if (nv21) {
                        int yv = data.get(y * sw + x) & 0xFF;
                        int uvIndex = uvOffset + (y >> 1) * uvRowStride + (x & ~1);
                        int v = (data.get(uvIndex) & 0xFF) - 128;
                        int u = (data.get(uvIndex + 1) & 0xFF) - 128;
                        // BT.601 full-range integer approximation (Android's standard NV21 → RGB)
                        int c = yv;
                        int rr = c + ((91881 * v) >> 16);
                        int gg = c - ((22554 * u + 46802 * v) >> 16);
                        int bb = c + ((116130 * u) >> 16);
                        r = normalise(clamp(rr, 0, 255));
                        g = normalise(clamp(gg, 0, 255));
                        b = normalise(clamp(bb, 0, 255));
                    } else {
                        int p = (y * sw + x) * 3;
                        r = normalise(data.get(p) & 0xFF);
                        g = normalise(data.get(p + 1) & 0xFF);
                        b = normalise(data.get(p + 2) & 0xFF);
                    }
                } else {
                    r = pad; g = pad; b = pad;
                }
                int pix = dy * mw + dx;
                if (nchw) {
                    tensor[pix] = r;
                    tensor[plane + pix] = g;
                    tensor[2 * plane + pix] = b;
                } else {
                    tensor[pix * 3] = r;
                    tensor[pix * 3 + 1] = g;
                    tensor[pix * 3 + 2] = b;
                }
            }
        }
        return t;
    }

    private float normalise(int v) {
        return spec.normalization() == ModelSpec.Normalization.SCALE_0_1 ? v / 255f : v;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
