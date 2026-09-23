package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 1.1: Frame.timestampNanos is the SOURCE/IMAGE timestamp and is propagated untouched;
 * telemetry FPS/durations come from the pipeline's own clock. The two domains must never mix.
 */
class FrameTimestampDomainTest {

    static final class OneShotSource implements FrameSource {
        Listener l;
        @Override public void start(Listener listener) { l = listener; }
        @Override public void stop() { l = null; }
        @Override public boolean isRunning() { return l != null; }
    }

    @Test
    void sourceTimestampPropagatesUnchangedWhileTelemetryUsesLocalClock() throws Exception {
        // Source clock domain: huge values far from the pipeline clock (like a camera boottime clock).
        long imageTs1 = 9_000_000_000_000L;
        long imageTs2 = imageTs1 + 33_000_000L;
        AtomicLong localClock = new AtomicLong(1_000L); // tiny, clearly a different domain
        OneShotSource src = new OneShotSource();
        AtomicLong seen = new AtomicLong();
        CountDownLatch done = new CountDownLatch(2);
        FrameProcessor proc = f -> { seen.set(f.timestampNanos()); done.countDown(); };
        PipelineTelemetry tel = new PipelineTelemetry();
        FramePipeline p = new FramePipeline(src, proc, tel, new LatestFrameQueue(), () -> localClock.addAndGet(10_000_000L));
        p.start();
        ByteBuffer b = ByteBuffer.allocate(6);
        src.l.onFrame(new Frame(2, 1, Frame.PixelFormat.RGB_888, b, 0, imageTs1, Frame.CameraSource.ROAD));
        awaitProcessed(tel, 1);
        src.l.onFrame(new Frame(2, 1, Frame.PixelFormat.RGB_888, b, 0, imageTs2, Frame.CameraSource.ROAD));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        awaitProcessed(tel, 2);
        PipelineTelemetry.Snapshot s = tel.snapshot();
        assertEquals(imageTs2, s.latestFrameTimestampNanos(), "image timestamp stored as-is");
        assertEquals(imageTs2, seen.get(), "processor sees the source timestamp");
        // Processing durations derived only from the local clock: 10 ms per tick step, never ~9e12.
        assertEquals(10.0, s.lastProcessingMillis(), 1e-9);
        assertTrue(s.receivedFps() > 0 && s.receivedFps() < 1000, "fps from local clock, not image clock");
        p.stop();
    }

    @Test
    void syntheticSourceUsesItsInjectedClockForTimestamps() throws Exception {
        AtomicLong srcClock = new AtomicLong(500_000L);
        SyntheticFrameSource src = new SyntheticFrameSource(4, 4, 0, 30, 3, () -> srcClock.addAndGet(1_000L), n -> { });
        CountDownLatch done = new CountDownLatch(3);
        long[] ts = new long[3];
        src.start(new FrameSource.Listener() {
            int i;
            @Override public void onFrame(Frame f) { if (i < 3) ts[i++] = f.timestampNanos(); done.countDown(); }
            @Override public void onSourceError(String d, Throwable c) { }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(501_000L, ts[0]);
        assertEquals(1_000L, ts[1] - ts[0], "consecutive same-source deltas are meaningful");
        assertEquals(1_000L, ts[2] - ts[1]);
    }

    private static void awaitProcessed(PipelineTelemetry tel, long n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (tel.processedFrames() < n) {
            if (System.nanoTime() > deadline) throw new AssertionError("timeout waiting for " + n);
            Thread.sleep(2);
        }
    }
}
