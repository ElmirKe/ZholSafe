package kz.zholsafe.ai;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Platform-independent view of one camera frame handed to the inference pipeline.
 *
 * <p>Android-specific image types (ImageProxy, Bitmap) are converted to this at the camera
 * boundary so that the core module has NO Android dependency and can be tested on the JVM.
 *
 * <h2>Orientation (Stage 1)</h2>
 * {@code width}/{@code height} describe the buffer <em>as stored</em> (sensor/image orientation,
 * NOT rotated). {@code rotationDegrees} (0/90/180/270, clockwise) is the rotation that must be
 * applied to the stored image to make it upright relative to the device's natural display
 * orientation — the same semantics as CameraX {@code ImageInfo.getRotationDegrees()}. Stage 1
 * does NOT rotate pixels (that would cost a full copy per frame); Stage 2 preprocessing must
 * apply the rotation while building the model input tensor and must map detections back into
 * this frame's stored coordinate system (or document that boxes are in upright coordinates).
 *
 * <h2>Timestamp semantics (Stage 1.1)</h2>
 * {@code timestampNanos} is the <em>source/image</em> timestamp: for the LIVE road camera it is
 * CameraX {@code ImageInfo.getTimestamp()} (camera capture time); for DEMO/TEST sources it is
 * whatever monotonic clock that source uses. It is NOT wall-clock time and NOT the time the frame
 * reached the pipeline. Timestamps are only comparable between consecutive frames of the same
 * {@link CameraSource}; tracking (Stage 3) and image trajectory (Stage 4.0) / future TTC (Stage 4.1) must difference those and must never
 * subtract a Frame timestamp from {@code System.nanoTime()} or from another source's timestamp.
 * Processing-duration and FPS telemetry use the pipeline's own monotonic clock instead.
 *
 * <h2>Buffer ownership</h2>
 * The buffer belongs to the producer and may be recycled after the consumer returns from
 * processing. Consumers MUST NOT retain {@code data} beyond the processing call; copy what you
 * need. For {@link PixelFormat#NV21} the buffer is tightly packed: {@code width*height} Y bytes
 * followed by {@code width*height/2} interleaved VU bytes.
 *
 * @param width           stored pixel width
 * @param height          stored pixel height
 * @param format          pixel layout of {@code data}
 * @param data            pixel data (position 0, limit = frame size)
 * @param rotationDegrees 0, 90, 180 or 270 — see above
 * @param timestampNanos  source/image capture timestamp in ns (see "Timestamp semantics"); same-source comparisons only
 * @param source          which camera produced the frame
 */
public record Frame(
        int width,
        int height,
        PixelFormat format,
        ByteBuffer data,
        int rotationDegrees,
        long timestampNanos,
        CameraSource source) {

    public enum PixelFormat { RGB_888, RGBA_8888, NV21, YUV_420_888 }

    public enum CameraSource { ROAD, DRIVER, DEMO_FILE, DEMO_SYNTHETIC, TEST }

    public Frame {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(source, "source");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("frame dimensions must be positive");
        }
        if (rotationDegrees != 0 && rotationDegrees != 90 && rotationDegrees != 180 && rotationDegrees != 270) {
            throw new IllegalArgumentException("rotationDegrees must be 0/90/180/270, got " + rotationDegrees);
        }
    }

    /** Width after applying {@link #rotationDegrees()} (what the user sees as "upright"). */
    public int uprightWidth() {
        return (rotationDegrees == 90 || rotationDegrees == 270) ? height : width;
    }

    public int uprightHeight() {
        return (rotationDegrees == 90 || rotationDegrees == 270) ? width : height;
    }

    /** Expected byte length of a tightly packed buffer in {@code format}, or -1 if not fixed. */
    public static int packedSize(PixelFormat format, int width, int height) {
        switch (format) {
            case RGB_888: return width * height * 3;
            case RGBA_8888: return width * height * 4;
            case NV21: return width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2);
            case YUV_420_888:
            default: return -1;
        }
    }
}
