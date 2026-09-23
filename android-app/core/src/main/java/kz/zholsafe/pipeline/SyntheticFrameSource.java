package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * DEMO / TEST frame source: deterministic synthetic NV21 frames at a fixed rate, no hardware.
 *
 * <p>Same {@link FrameSource} contract as the CameraX road camera, so the downstream pipeline
 * (queue → processor → telemetry) is identical. Frames are clearly tagged
 * {@link Frame.CameraSource#DEMO_SYNTHETIC}; the UI must label them as DEMO. Content is a moving
 * luma gradient so the diagnostic processor produces changing statistics. Two buffers are
 * alternated (double-buffering) — allocation per frame is zero after start.
 *
 * <p>Not a video player by design (Stage 1). A file-backed DEMO source can implement the same
 * interface later.
 */
public final class SyntheticFrameSource implements FrameSource {

    private final int width;
    private final int height;
    private final int rotationDegrees;
    private final long intervalNanos;
    private final long maxFrames; // <= 0 means unlimited
    private final LongSupplier clock;
    private final SleepFn sleeper;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;
    private volatile long produced;

    /** Injectable sleep so tests can run without real waiting. */
    public interface SleepFn {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    public SyntheticFrameSource(int width, int height, int rotationDegrees, double fps) {
        this(width, height, rotationDegrees, fps, 0, System::nanoTime,
                n -> Thread.sleep(n / 1_000_000L, (int) (n % 1_000_000L)));
    }

    public SyntheticFrameSource(int width, int height, int rotationDegrees, double fps, long maxFrames,
                                LongSupplier clock, SleepFn sleeper) {
        if (fps <= 0) {
            throw new IllegalArgumentException("fps must be > 0");
        }
        this.width = width;
        this.height = height;
        this.rotationDegrees = rotationDegrees;
        this.intervalNanos = (long) (1e9 / fps);
        this.maxFrames = maxFrames;
        this.clock = Objects.requireNonNull(clock);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    @Override
    public void start(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> run(listener), "zs-demo-source");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void run(Listener listener) {
        int size = Frame.packedSize(Frame.PixelFormat.NV21, width, height);
        ByteBuffer[] buffers = { ByteBuffer.allocate(size), ByteBuffer.allocate(size) };
        long n = 0;
        try {
            while (running.get() && (maxFrames <= 0 || n < maxFrames)) {
                ByteBuffer buf = buffers[(int) (n & 1)];
                fill(buf, n);
                buf.clear();
                listener.onFrame(new Frame(width, height, Frame.PixelFormat.NV21, buf, rotationDegrees,
                        clock.getAsLong(), Frame.CameraSource.DEMO_SYNTHETIC));
                n++;
                produced = n;
                sleeper.sleepNanos(intervalNanos);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            listener.onSourceError("synthetic source failed: " + e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    /** Deterministic content: luma = (x + y + 3*frameIndex) mod 256, chroma neutral. */
    private void fill(ByteBuffer buf, long frameIndex) {
        byte[] a = buf.array();
        int yLen = width * height;
        int shift = (int) ((frameIndex * 3) & 0xFF);
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                a[row + x] = (byte) ((x + y + shift) & 0xFF);
            }
        }
        for (int i = yLen; i < a.length; i++) {
            a[i] = (byte) 128;
        }
    }

    @Override
    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    public long producedCount() {
        return produced;
    }
}
