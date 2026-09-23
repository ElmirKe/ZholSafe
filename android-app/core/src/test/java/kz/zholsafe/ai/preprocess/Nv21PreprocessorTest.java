package kz.zholsafe.ai.preprocess;

import kz.zholsafe.ai.Frame;
import kz.zholsafe.ai.TestSpecs;
import kz.zholsafe.ai.spec.ModelSpec;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pixel-level checks. Uses NV21 frames with neutral chroma (128) so Y == R == G == B exactly,
 * which lets us assert rotation and letterbox placement by luma value.
 */
class Nv21PreprocessorTest {

    /** Stored buffer W×H whose luma at (x,y) = 10*y + x (unique per pixel for small sizes). */
    private static Frame nv21(int w, int h, int rotation) {
        ByteBuffer b = ByteBuffer.allocate(Frame.packedSize(Frame.PixelFormat.NV21, w, h));
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) b.put(y * w + x, (byte) (10 * y + x));
        for (int i = w * h; i < b.capacity(); i++) b.put(i, (byte) 128);
        return new Frame(w, h, Frame.PixelFormat.NV21, b, rotation, 1L, Frame.CameraSource.TEST);
    }

    /** Reads channel c of destination pixel (dx,dy) from an NCHW tensor of size mw×mh. */
    private static float px(float[] t, int mw, int mh, int dx, int dy, int c) {
        return t[c * mw * mh + dy * mw + dx];
    }

    @Test
    void rotation0IsIdentity() {
        // 4×2 stored, model 4×2, no letterbox needed (same aspect), scale 1
        ModelSpec spec = TestSpecs.rawNoNorm(4, 2, true, ModelSpec.TensorLayout.NCHW);
        Nv21Preprocessor p = new Nv21Preprocessor(spec);
        p.process(nv21(4, 2, 0));
        float[] t = p.tensor();
        for (int y = 0; y < 2; y++) for (int x = 0; x < 4; x++) assertEquals(10 * y + x, px(t, 4, 2, x, y, 0), "(" + x + "," + y + ")");
    }

    @Test
    void rotation90MapsUprightToStored() {
        // stored 4×2 (W=4,H=2); upright is 2×4. Model 2×4.
        ModelSpec spec = TestSpecs.rawNoNorm(2, 4, true, ModelSpec.TensorLayout.NCHW);
        Nv21Preprocessor p = new Nv21Preprocessor(spec);
        Frame f = nv21(4, 2, 90);
        assertEquals(2, f.uprightWidth());
        assertEquals(4, f.uprightHeight());
        LetterboxTransform lb = p.process(f);
        assertEquals(0, lb.padX());
        assertEquals(0, lb.padY());
        float[] t = p.tensor();
        // 90° clockwise: upright(ux,uy) ← stored(x=uy, y=H-1-ux)
        for (int uy = 0; uy < 4; uy++) for (int ux = 0; ux < 2; ux++) {
            int x = uy;
            int y = 2 - 1 - ux;
            assertEquals(10 * y + x, px(t, 2, 4, ux, uy, 0), "upright(" + ux + "," + uy + ")");
        }
        // Concretely: upright top-left is stored bottom-left (x=0, y=1) → luma 10
        assertEquals(10f, px(t, 2, 4, 0, 0, 0));
        // upright top-right is stored top-left (x=0,y=0) → luma 0
        assertEquals(0f, px(t, 2, 4, 1, 0, 0));
    }

    @Test
    void rotation180FlipsBothAxes() {
        ModelSpec spec = TestSpecs.rawNoNorm(4, 2, true, ModelSpec.TensorLayout.NCHW);
        Nv21Preprocessor p = new Nv21Preprocessor(spec);
        p.process(nv21(4, 2, 180));
        float[] t = p.tensor();
        for (int uy = 0; uy < 2; uy++) for (int ux = 0; ux < 4; ux++) {
            assertEquals(10 * (1 - uy) + (3 - ux), px(t, 4, 2, ux, uy, 0));
        }
    }

    @Test
    void rotation270MapsUprightToStored() {
        ModelSpec spec = TestSpecs.rawNoNorm(2, 4, true, ModelSpec.TensorLayout.NCHW);
        Nv21Preprocessor p = new Nv21Preprocessor(spec);
        p.process(nv21(4, 2, 270));
        float[] t = p.tensor();
        // 270° clockwise: upright(ux,uy) ← stored(x=W-1-uy, y=ux)
        for (int uy = 0; uy < 4; uy++) for (int ux = 0; ux < 2; ux++) {
            int x = 4 - 1 - uy;
            int y = ux;
            assertEquals(10 * y + x, px(t, 2, 4, ux, uy, 0));
        }
        // upright top-left is stored top-right (x=3,y=0) → 3
        assertEquals(3f, px(t, 2, 4, 0, 0, 0));
    }

    @Test
    void letterboxPadsWithPadValueAndKeepsContentCentred() {
        // upright 4×2 into 4×4 model → content rows 1..2, pad rows 0 and 3
        ModelSpec spec = TestSpecs.rawNoNorm(4, 4, true, ModelSpec.TensorLayout.NCHW);
        Nv21Preprocessor p = new Nv21Preprocessor(spec);
        LetterboxTransform lb = p.process(nv21(4, 2, 0));
        assertEquals(1, lb.padY());
        float[] t = p.tensor();
        for (int x = 0; x < 4; x++) {
            assertEquals(114f, px(t, 4, 4, x, 0, 0));
            assertEquals(114f, px(t, 4, 4, x, 3, 0));
            assertEquals(x, px(t, 4, 4, x, 1, 0));       // row 0 of source
            assertEquals(10 + x, px(t, 4, 4, x, 2, 0));  // row 1 of source
        }
    }

    @Test
    void portraitLetterboxPadsLeftRight() {
        // stored 4×2 rotated 90 → upright 2×4 into 4×4: content cols 1..2
        ModelSpec spec = TestSpecs.rawNoNorm(4, 4, true, ModelSpec.TensorLayout.NCHW);
        Nv21Preprocessor p = new Nv21Preprocessor(spec);
        LetterboxTransform lb = p.process(nv21(4, 2, 90));
        assertEquals(1, lb.padX());
        assertEquals(0, lb.padY());
        float[] t = p.tensor();
        for (int y = 0; y < 4; y++) {
            assertEquals(114f, px(t, 4, 4, 0, y, 0));
            assertEquals(114f, px(t, 4, 4, 3, y, 0));
            assertTrue(px(t, 4, 4, 1, y, 0) != 114f || y == 0); // content present (luma 10 at (0,0) upright...)
        }
        assertEquals(10f, px(t, 4, 4, 1, 0, 0)); // upright (0,0) → stored (0,1) → 10
    }

    @Test
    void downscaleSamplesNearestNeighbour() {
        // 8×8 source → 4×4 model, scale 0.5: dst (dx) ← src ((dx+0.5)*2) = 1,3,5,7
        ByteBuffer b = ByteBuffer.allocate(Frame.packedSize(Frame.PixelFormat.NV21, 8, 8));
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) b.put(y * 8 + x, (byte) (x * 16 + y));
        for (int i = 64; i < b.capacity(); i++) b.put(i, (byte) 128);
        Frame f = new Frame(8, 8, Frame.PixelFormat.NV21, b, 0, 1L, Frame.CameraSource.TEST);
        Nv21Preprocessor p = new Nv21Preprocessor(TestSpecs.rawNoNorm(4, 4, true, ModelSpec.TensorLayout.NCHW));
        p.process(f);
        assertEquals(1 * 16 + 1, px(p.tensor(), 4, 4, 0, 0, 0));
        assertEquals(7 * 16 + 7, px(p.tensor(), 4, 4, 3, 3, 0));
    }

    @Test
    void normalisationAndNhwcLayout() {
        ModelSpec spec = TestSpecs.raw(2, 5, 0.25f, 0.5f); // SCALE_0_1, NCHW, 2×2
        Nv21Preprocessor p = new Nv21Preprocessor(spec);
        ByteBuffer b = ByteBuffer.allocate(Frame.packedSize(Frame.PixelFormat.NV21, 2, 2));
        for (int i = 0; i < 4; i++) b.put(i, (byte) 255);
        for (int i = 4; i < 6; i++) b.put(i, (byte) 128);
        p.process(new Frame(2, 2, Frame.PixelFormat.NV21, b, 0, 1L, Frame.CameraSource.TEST));
        for (float v : p.tensor()) assertEquals(1f, v, 1e-6);

        ModelSpec nhwc = TestSpecs.rawNoNorm(2, 2, true, ModelSpec.TensorLayout.NHWC);
        Nv21Preprocessor q = new Nv21Preprocessor(nhwc);
        ByteBuffer rgb = ByteBuffer.allocate(12);
        rgb.put(0, (byte) 10).put(1, (byte) 20).put(2, (byte) 30); // pixel 0
        q.process(new Frame(2, 2, Frame.PixelFormat.RGB_888, rgb, 0, 1L, Frame.CameraSource.TEST));
        assertEquals(10f, q.tensor()[0]);
        assertEquals(20f, q.tensor()[1]);
        assertEquals(30f, q.tensor()[2]);
    }

    @Test
    void chromaConvertsToColour() {
        // Y=128, V=255 (red-ish), U=128 → R > G, R > B
        ByteBuffer b = ByteBuffer.allocate(Frame.packedSize(Frame.PixelFormat.NV21, 2, 2));
        for (int i = 0; i < 4; i++) b.put(i, (byte) 128);
        b.put(4, (byte) 255); // V
        b.put(5, (byte) 128); // U
        Nv21Preprocessor p = new Nv21Preprocessor(TestSpecs.rawNoNorm(2, 2, true, ModelSpec.TensorLayout.NCHW));
        p.process(new Frame(2, 2, Frame.PixelFormat.NV21, b, 0, 1L, Frame.CameraSource.TEST));
        float r = px(p.tensor(), 2, 2, 0, 0, 0);
        float g = px(p.tensor(), 2, 2, 0, 0, 1);
        float bl = px(p.tensor(), 2, 2, 0, 0, 2);
        assertTrue(r > g && r > bl, "r=" + r + " g=" + g + " b=" + bl);
        assertEquals(255f, r);
    }

    @Test
    void tensorIsReusedAcrossCalls() {
        Nv21Preprocessor p = new Nv21Preprocessor(TestSpecs.rawNoNorm(4, 2, true, ModelSpec.TensorLayout.NCHW));
        float[] a = p.tensor();
        p.process(nv21(4, 2, 0));
        p.process(nv21(4, 2, 180));
        assertTrue(a == p.tensor());
    }
}
