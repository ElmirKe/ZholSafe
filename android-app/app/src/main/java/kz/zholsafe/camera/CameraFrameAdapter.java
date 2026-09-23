package kz.zholsafe.camera;

import androidx.camera.core.ImageProxy;

import kz.zholsafe.ai.Frame;
import kz.zholsafe.pipeline.FrameBufferRecycler;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Converts a CameraX {@link ImageProxy} (YUV_420_888) into a core {@link Frame} (packed NV21).
 *
 * <h2>Ownership rules (Stage 1)</h2>
 * <ul>
 *   <li>The {@link ImageProxy} never leaves this class. Only its pixel bytes, dimensions, rotation
 *       and timestamp are copied out. The caller ({@link LiveCamera}) closes the proxy in a
 *       {@code finally} block on every path.</li>
 *   <li>Output buffers come from a fixed pool of {@value #POOL_SIZE} direct buffers sized for the
 *       current resolution — one being filled, one pending in the queue, one being processed.
 *       {@link #recycle(Frame)} returns a buffer to the pool; if the pool is empty the frame is
 *       skipped ({@link #convert} returns {@code null}) rather than allocating. Zero steady-state
 *       allocation besides the small {@code Frame} record.</li>
 *   <li>No Bitmap, no rotation, no colour conversion: NV21 is the cheapest layout that keeps full
 *       luma + chroma for Stage 2 preprocessing.</li>
 * </ul>
 */
final class CameraFrameAdapter implements FrameBufferRecycler {

    static final int POOL_SIZE = 3;

    private final Frame.CameraSource source;
    private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>(POOL_SIZE);
    private int pooledWidth = -1;
    private int pooledHeight = -1;
    private long skippedNoBuffer;

    CameraFrameAdapter(Frame.CameraSource source) {
        this.source = source;
    }

    /**
     * @return a Frame backed by a pooled buffer, or {@code null} if the pool is exhausted or the
     *         image format is not YUV_420_888. Never throws for pool exhaustion.
     */
    Frame convert(ImageProxy image, long timestampNanos) {
        if (image.getFormat() != android.graphics.ImageFormat.YUV_420_888 || image.getPlanes().length < 3) {
            throw new IllegalStateException("unsupported ImageProxy format " + image.getFormat());
        }
        int w = image.getWidth();
        int h = image.getHeight();
        ByteBuffer out = acquire(w, h);
        if (out == null) {
            skippedNoBuffer++;
            return null;
        }
        try {
            copyYuv420ToNv21(image, w, h, out);
        } catch (RuntimeException e) {
            recycleBuffer(out);
            throw e;
        }
        out.clear();
        return new Frame(w, h, Frame.PixelFormat.NV21, out, image.getImageInfo().getRotationDegrees(),
                timestampNanos, source);
    }

    /** Called from the processing thread (after process) and the analysis thread (on displacement). */
    @Override
    public void recycle(Frame frame) {
        if (frame.source() == source) {
            recycleBuffer(frame.data());
        }
    }

    long skippedNoBufferCount() {
        return skippedNoBuffer;
    }

    synchronized int availableBuffers() {
        return pool.size();
    }

    /** Drops all pooled buffers (resolution change / shutdown). */
    synchronized void clear() {
        pool.clear();
        pooledWidth = -1;
        pooledHeight = -1;
    }

    private synchronized ByteBuffer acquire(int w, int h) {
        int size = Frame.packedSize(Frame.PixelFormat.NV21, w, h);
        if (w != pooledWidth || h != pooledHeight) {
            pool.clear();
            for (int i = 0; i < POOL_SIZE; i++) {
                pool.add(ByteBuffer.allocateDirect(size));
            }
            pooledWidth = w;
            pooledHeight = h;
        }
        return pool.pollFirst();
    }

    private synchronized void recycleBuffer(ByteBuffer buf) {
        if (buf.capacity() == Frame.packedSize(Frame.PixelFormat.NV21, pooledWidth, pooledHeight)
                && pool.size() < POOL_SIZE) {
            buf.clear();
            pool.addLast(buf);
        }
        // else: stale buffer from a previous resolution — let GC take it.
    }

    // ---- YUV_420_888 → NV21 ----

    static void copyYuv420ToNv21(ImageProxy image, int w, int h, ByteBuffer out) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        out.clear();
        copyPlane(planes[0].getBuffer(), planes[0].getRowStride(), planes[0].getPixelStride(), w, h, out);

        int cw = (w + 1) / 2;
        int ch = (h + 1) / 2;
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int uRow = planes[1].getRowStride();
        int vRow = planes[2].getRowStride();
        int uPix = planes[1].getPixelStride();
        int vPix = planes[2].getPixelStride();

        if (uPix == 2 && vPix == 2 && uRow == vRow && isNv21Interleaved(u, v)) {
            // Fast path (most HALs): U and V are views of one interleaved VUVU… buffer, so each
            // chroma row of the V view is already NV21 byte order. Bulk-copy row by row.
            int rowBytes = cw * 2;
            ByteBuffer row = v.duplicate();
            for (int r = 0; r < ch; r++) {
                int start = r * vRow;
                int len = Math.min(rowBytes, v.limit() - start);
                row.limit(start + len).position(start);
                out.put(row);
                if (len < rowBytes) {
                    // The V view is one byte shorter than U on the very last row; that missing
                    // byte is U's last sample.
                    out.put(u.get(r * uRow + (cw - 1) * uPix));
                }
            }
            return;
        }
        // Generic path: arbitrary strides / planar chroma. Per-sample copy.
        for (int r = 0; r < ch; r++) {
            int vBase = r * vRow;
            int uBase = r * uRow;
            for (int c = 0; c < cw; c++) {
                out.put(v.get(vBase + c * vPix));
                out.put(u.get(uBase + c * uPix));
            }
        }
    }

    private static void copyPlane(ByteBuffer src, int rowStride, int pixelStride, int w, int h, ByteBuffer out) {
        if (pixelStride == 1 && rowStride == w) {
            ByteBuffer s = src.duplicate();
            s.position(0).limit(w * h);
            out.put(s);
            return;
        }
        if (pixelStride == 1) {
            ByteBuffer s = src.duplicate();
            for (int r = 0; r < h; r++) {
                int start = r * rowStride;
                s.limit(start + w).position(start);
                out.put(s);
            }
            return;
        }
        for (int r = 0; r < h; r++) {
            int base = r * rowStride;
            for (int c = 0; c < w; c++) {
                out.put(src.get(base + c * pixelStride));
            }
        }
    }

    /**
     * True when U and V appear to be views of one interleaved buffer (V at offset 0, U at offset
     * 1): limits differ by at most one byte and the first few samples cross-match. Cheap check,
     * verified per frame so a HAL change at runtime cannot silently corrupt chroma.
     */
    private static boolean isNv21Interleaved(ByteBuffer u, ByteBuffer v) {
        if (Math.abs(u.limit() - v.limit()) > 1 || v.limit() < 8 || u.limit() < 8) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (u.get(i * 2) != v.get(i * 2 + 1)) {
                return false;
            }
        }
        return true;
    }
}
