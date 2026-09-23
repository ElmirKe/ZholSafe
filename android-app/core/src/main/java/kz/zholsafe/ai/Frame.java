package kz.zholsafe.ai;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Platform-independent view of one camera frame handed to the inference pipeline.
 *
 * <p>Android-specific image types (ImageProxy, Bitmap) are converted to this at the camera
 * boundary so that the core module has NO Android dependency and can be tested on the JVM.
 * The buffer is owned by the producer; consumers must not retain it after {@code close()}.
 *
 * @param width          pixel width after rotation
 * @param height         pixel height after rotation
 * @param format         pixel layout of {@code data}
 * @param data           pixel data (read-only view recommended)
 * @param timestampNanos monotonic capture timestamp (System.nanoTime domain)
 * @param source         which camera produced the frame
 */
public record Frame(
        int width,
        int height,
        PixelFormat format,
        ByteBuffer data,
        long timestampNanos,
        CameraSource source) {

    public enum PixelFormat { RGB_888, RGBA_8888, NV21, YUV_420_888 }

    public enum CameraSource { ROAD, DRIVER, DEMO_FILE, TEST }

    public Frame {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(source, "source");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("frame dimensions must be positive");
        }
    }
}
