package kz.zholsafe.smoke;

import kz.zholsafe.ai.Frame;
import kz.zholsafe.ai.preprocess.LetterboxTransform;
import kz.zholsafe.ai.preprocess.Nv21Preprocessor;
import kz.zholsafe.ai.spec.ModelSpec;
import kz.zholsafe.ai.spec.ModelSpecParser;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the harness' RGB→NV21 + synthetic-rotation input against the PRODUCTION preprocessor. */
class ImageFramesTest {

    private static final String SPEC = "{"
            + "\"modelId\":\"t\",\"family\":\"yolo11\",\"version\":\"t\",\"modelFile\":\"m.onnx\",\"labelsFile\":\"l.txt\","
            + "\"inputName\":\"images\",\"outputName\":\"output0\",\"inputWidth\":64,\"inputHeight\":64,\"inputChannels\":3,"
            + "\"layout\":\"NCHW\",\"inputType\":\"FLOAT32\",\"normalization\":\"SCALE_0_1\",\"letterbox\":true,\"padValue\":114,"
            + "\"confidenceThreshold\":0.3,\"iouThreshold\":0.5,\"decoder\":\"YOLO_RAW_CXCYWH_NC\",\"nmsInModel\":false,"
            + "\"numClasses\":80,\"maxDetections\":10,\"labelAliases\":{},\"sha256\":null}";

    private static final ModelSpec SPEC_64 = ModelSpecParser.parse(SPEC);

    /** Quadrant image: TL red, TR green, BL blue, BR white; 40x24 (landscape). */
    private static BufferedImage quadrants() {
        BufferedImage img = new BufferedImage(40, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED); g.fillRect(0, 0, 20, 12);
        g.setColor(Color.GREEN); g.fillRect(20, 0, 20, 12);
        g.setColor(Color.BLUE); g.fillRect(0, 12, 20, 12);
        g.setColor(Color.WHITE); g.fillRect(20, 12, 20, 12);
        g.dispose();
        return img;
    }

    private static float[] rgbAt(Nv21Preprocessor pp, LetterboxTransform lb, int ux, int uy) {
        // upright source pixel → model pixel
        int mx = lb.padX() + (int) (ux * lb.scale());
        int my = lb.padY() + (int) (uy * lb.scale());
        int plane = 64 * 64;
        int p = my * 64 + mx;
        float[] t = pp.tensor();
        return new float[]{t[p], t[plane + p], t[2 * plane + p]};
    }

    private static void assertColour(float[] rgb, int r, int g, int b) {
        assertEquals(r / 255f, rgb[0], 0.03f, "R");
        assertEquals(g / 255f, rgb[1], 0.03f, "G");
        assertEquals(b / 255f, rgb[2], 0.03f, "B");
    }

    @Test
    void nv21RoundTripPreservesChannelOrder() {
        BufferedImage img = quadrants();
        Nv21Preprocessor pp = new Nv21Preprocessor(SPEC_64);
        LetterboxTransform lb = pp.process(ImageFrames.nv21(img, 0, 1L));
        assertColour(rgbAt(pp, lb, 5, 3), 255, 0, 0);
        assertColour(rgbAt(pp, lb, 30, 3), 0, 255, 0);
        assertColour(rgbAt(pp, lb, 5, 18), 0, 0, 255);
        assertColour(rgbAt(pp, lb, 30, 18), 255, 255, 255);
    }

    @Test
    void rgb888AndNv21PathsAgree() {
        BufferedImage img = quadrants();
        Nv21Preprocessor a = new Nv21Preprocessor(SPEC_64);
        Nv21Preprocessor b = new Nv21Preprocessor(SPEC_64);
        a.process(ImageFrames.rgb888(img, 1L));
        b.process(ImageFrames.nv21(img, 0, 1L));
        float[] ta = a.tensor();
        float[] tb = b.tensor();
        double maxDiff = 0;
        for (int i = 0; i < ta.length; i++) maxDiff = Math.max(maxDiff, Math.abs(ta[i] - tb[i]));
        assertTrue(maxDiff < 0.03, "NV21 round trip differs from RGB path by " + maxDiff);
    }

    @Test
    void syntheticRotationsRestoreUprightImage() {
        BufferedImage img = quadrants();
        for (int rot : new int[]{90, 180, 270}) {
            Frame f = ImageFrames.nv21(img, rot, 1L);
            assertEquals(40, f.uprightWidth(), "rot " + rot);
            assertEquals(24, f.uprightHeight(), "rot " + rot);
            if (rot == 90 || rot == 270) {
                assertEquals(24, f.width());
                assertEquals(40, f.height());
            }
            Nv21Preprocessor pp = new Nv21Preprocessor(SPEC_64);
            LetterboxTransform lb = pp.process(f);
            assertColour(rgbAt(pp, lb, 5, 3), 255, 0, 0);
            assertColour(rgbAt(pp, lb, 30, 3), 0, 255, 0);
            assertColour(rgbAt(pp, lb, 5, 18), 0, 0, 255);
            assertColour(rgbAt(pp, lb, 30, 18), 255, 255, 255);
        }
    }

    @Test
    void nv21LayoutIsYThenVU() {
        byte[] rgb = new byte[2 * 2 * 3];
        for (int i = 0; i < 4; i++) { rgb[i * 3] = (byte) 255; rgb[i * 3 + 1] = 0; rgb[i * 3 + 2] = 0; } // pure red
        byte[] nv = ImageFrames.rgbToNv21(rgb, 2, 2);
        assertEquals(6, nv.length);
        assertEquals(76, nv[0] & 0xFF, 1);   // Y of red (BT.601 full range)
        assertEquals(255, nv[4] & 0xFF, 1);  // V first
        assertEquals(85, nv[5] & 0xFF, 1);   // then U
    }

    @Test
    void jsonRoundTripOfManifestShapes() {
        Object v = JsonReader.parse("{\"images\":[{\"file\":\"a.jpg\",\"expected\":[\"DOG\"],\"rotation_test\":true,\"n\":1.5}]}");
        String out = Json.write(v);
        assertTrue(out.contains("\"file\": \"a.jpg\""));
        assertTrue(out.contains("\"rotation_test\": true"));
        assertTrue(out.contains("[\"DOG\"]"));
    }
}
