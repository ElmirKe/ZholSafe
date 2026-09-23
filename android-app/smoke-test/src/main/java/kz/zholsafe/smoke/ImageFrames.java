package kz.zholsafe.smoke;

import kz.zholsafe.ai.Frame;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Builds production {@link Frame}s from decoded desktop images.
 *
 * <p><b>RGB → NV21.</b> The production preprocessor consumes CameraX NV21 and converts it with the
 * BT.601 full-range integer approximation. This class applies the matching forward transform
 * (JFIF / full-range BT.601, chroma averaged over each 2×2 block, V before U per NV21) so that
 * the smoke test exercises the same NV21 → RGB path as the phone. The round trip is lossy by at
 * most a few code values per channel (chroma subsampling + integer rounding); that is expected
 * and is exactly what CameraX frames look like. Channel ORDER is preserved: red stays red.
 *
 * <p><b>Synthetic rotation.</b> {@link #nv21(BufferedImage, int, long)} produces the buffer a
 * camera would deliver when the sensor is rotated by {@code rotationDegrees} relative to the
 * upright picture, i.e. the exact inverse of the upright→stored mapping in
 * {@code Nv21Preprocessor}: 90: stored(x,y)=upright(sh-1-y, x); 180: stored(x,y)=upright(sw-1-x,
 * sh-1-y); 270: stored(x,y)=upright(y, sw-1-x). Detections must therefore come back in UPRIGHT
 * coordinates identical to the rotation-0 run (up to nearest-neighbour effects).
 */
final class ImageFrames {

    private ImageFrames() { }

    /** Upright RGB planes of an image as a packed byte[] (row-major, RGB). */
    static byte[] rgb(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] out = new byte[w * h * 3];
        int[] row = new int[w];
        int p = 0;
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                out[p++] = (byte) ((argb >> 16) & 0xFF);
                out[p++] = (byte) ((argb >> 8) & 0xFF);
                out[p++] = (byte) (argb & 0xFF);
            }
        }
        return out;
    }

    /** Upright RGB_888 frame (rotation 0). Used only for the colour-sanity comparison. */
    static Frame rgb888(BufferedImage img, long ts) {
        return new Frame(img.getWidth(), img.getHeight(), Frame.PixelFormat.RGB_888,
                ByteBuffer.wrap(rgb(img)), 0, ts, Frame.CameraSource.TEST);
    }

    /** NV21 frame whose STORED buffer is the upright image rotated so that {@code rotationDegrees} restores it. */
    static Frame nv21(BufferedImage img, int rotationDegrees, long ts) {
        int uw = img.getWidth();
        int uh = img.getHeight();
        byte[] up = rgb(img);
        int sw;
        int sh;
        if (rotationDegrees == 90 || rotationDegrees == 270) {
            sw = uh;
            sh = uw;
        } else {
            sw = uw;
            sh = uh;
        }
        // stored RGB buffer
        byte[] stored = new byte[sw * sh * 3];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int ux;
                int uy;
                switch (rotationDegrees) {
                    case 90: ux = sh - 1 - y; uy = x; break;
                    case 180: ux = sw - 1 - x; uy = sh - 1 - y; break;
                    case 270: ux = y; uy = sw - 1 - x; break;
                    default: ux = x; uy = y;
                }
                int s = (uy * uw + ux) * 3;
                int d = (y * sw + x) * 3;
                stored[d] = up[s];
                stored[d + 1] = up[s + 1];
                stored[d + 2] = up[s + 2];
            }
        }
        return new Frame(sw, sh, Frame.PixelFormat.NV21, ByteBuffer.wrap(rgbToNv21(stored, sw, sh)),
                rotationDegrees, ts, Frame.CameraSource.TEST);
    }

    /** Full-range BT.601 RGB → NV21 (Y plane, then interleaved V,U at 2×2 subsampling). */
    static byte[] rgbToNv21(byte[] rgb, int w, int h) {
        int ySize = w * h;
        int cw = (w + 1) / 2;
        int ch = (h + 1) / 2;
        byte[] out = new byte[ySize + 2 * cw * ch];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = (y * w + x) * 3;
                int r = rgb[p] & 0xFF;
                int g = rgb[p + 1] & 0xFF;
                int b = rgb[p + 2] & 0xFF;
                int yy = (299 * r + 587 * g + 114 * b + 500) / 1000;
                out[y * w + x] = (byte) clamp(yy);
            }
        }
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                long sr = 0;
                long sg = 0;
                long sb = 0;
                int n = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int x = cx * 2 + dx;
                        int y = cy * 2 + dy;
                        if (x < w && y < h) {
                            int p = (y * w + x) * 3;
                            sr += rgb[p] & 0xFF;
                            sg += rgb[p + 1] & 0xFF;
                            sb += rgb[p + 2] & 0xFF;
                            n++;
                        }
                    }
                }
                double r = (double) sr / n;
                double g = (double) sg / n;
                double b = (double) sb / n;
                int u = (int) Math.round(-0.168736 * r - 0.331264 * g + 0.5 * b + 128);
                int v = (int) Math.round(0.5 * r - 0.418688 * g - 0.081312 * b + 128);
                int idx = ySize + (cy * cw + cx) * 2;
                out[idx] = (byte) clamp(v);     // NV21: V first
                out[idx + 1] = (byte) clamp(u); // then U
            }
        }
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
