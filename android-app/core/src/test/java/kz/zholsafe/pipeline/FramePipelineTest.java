package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FramePipelineTest {

    /** Source driven manually from the test thread — no timing dependence. */
    static final class ManualSource implements FrameSource, FrameBufferRecycler {
        volatile Listener listener;
        final AtomicInteger recycled = new AtomicInteger();
        final AtomicInteger stopCalls = new AtomicInteger();
        boolean failOnStart;

        @Override public void start(Listener l) {
            if (failOnStart) throw new IllegalStateException("no rear camera");
            listener = l;
        }
        @Override public void stop() { stopCalls.incrementAndGet(); listener = null; }
        @Override public boolean isRunning() { return listener != null; }
        @Override public void recycle(Frame frame) { recycled.incrementAndGet(); }

        Frame emit(long ts) {
            Frame f = new Frame(4, 2, Frame.PixelFormat.NV21, ByteBuffer.allocate(12), 90, ts, Frame.CameraSource.TEST);
            listener.onFrame(f);
            return f;
        }
    }

    /** Processor that blocks until released, so the test controls backpressure precisely. */
    static final class GatedProcessor implements FrameProcessor {
        final java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(0);
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        volatile boolean throwOnce;

        @Override public void process(Frame frame) throws Exception {
            entered.countDown();
            gate.acquire();
            if (throwOnce) { throwOnce = false; throw new IllegalStateException("boom"); }
            processed.incrementAndGet();
        }
        @Override public void close() { closed.incrementAndGet(); }
    }

    private FramePipeline pipeline;

    @AfterEach
    void tearDown() {
        if (pipeline != null) pipeline.stop();
    }

    private static void awaitCondition(java.util.function.BooleanSupplier c) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timeout");
            Thread.sleep(2);
        }
    }

    @Test
    void latestFrameWinsAndDropsAreAccountedAndRecycled() throws Exception {
        ManualSource src = new ManualSource();
        GatedProcessor proc = new GatedProcessor();
        PipelineTelemetry tel = new PipelineTelemetry();
        AtomicLong clock = new AtomicLong();
        pipeline = new FramePipeline(src, proc, tel, new LatestFrameQueue(), clock::incrementAndGet);
        pipeline.start();
        assertEquals(PipelineState.STARTING, tel.state(), "RUNNING only once a frame arrives");

        src.emit(1);
        assertEquals(PipelineState.RUNNING, tel.state());
        assertTrue(proc.entered.await(2, TimeUnit.SECONDS)); // worker is now blocked in process(frame 1)
        src.emit(2);
        src.emit(3);
        src.emit(4); // 2 and 3 are displaced while worker is busy
        assertEquals(4, tel.receivedFrames());
        assertEquals(2, tel.droppedOrReplacedFrames());
        assertEquals(2, src.recycled.get(), "displaced frames recycled immediately");
        assertEquals(2, pipeline.queue().droppedCount());

        proc.gate.release(2); // finish frame 1, then frame 4
        awaitCondition(() -> proc.processed.get() == 2);
        awaitCondition(() -> src.recycled.get() == 4);
        assertEquals(2, tel.processedFrames());
        assertTrue(pipeline.queue().isEmpty(), "no accumulation: queue holds at most one frame");
        assertTrue(tel.snapshot().processedFps() >= 0);
    }

    @Test
    void processingExceptionIsCountedDegradesAndRecovers() throws Exception {
        ManualSource src = new ManualSource();
        GatedProcessor proc = new GatedProcessor();
        PipelineTelemetry tel = new PipelineTelemetry();
        pipeline = new FramePipeline(src, proc, tel, new LatestFrameQueue(), System::nanoTime);
        pipeline.start();
        proc.throwOnce = true;
        proc.gate.release(5);
        src.emit(1);
        awaitCondition(() -> tel.processingErrors() == 1);
        awaitCondition(() -> tel.state() == PipelineState.DEGRADED);
        awaitCondition(() -> src.recycled.get() == 1); // buffer released even on exception
        src.emit(2);
        awaitCondition(() -> tel.processedFrames() == 1);
        assertEquals(PipelineState.RUNNING, tel.state(), "recovers after a successful frame");
        assertTrue(pipeline.isRunning());
    }

    @Test
    void stopTerminatesExecutorDrainsQueueAndClosesProcessor() throws Exception {
        ManualSource src = new ManualSource();
        GatedProcessor proc = new GatedProcessor();
        PipelineTelemetry tel = new PipelineTelemetry();
        pipeline = new FramePipeline(src, proc, tel);
        pipeline.start();
        src.emit(1);
        assertTrue(proc.entered.await(2, TimeUnit.SECONDS));
        src.emit(2); // pending in queue while worker blocked
        proc.gate.release(1);
        awaitCondition(() -> proc.processed.get() == 1);
        // worker now blocked on take()? no — frame 2 is pending, so it enters process() again and blocks on gate.
        // Either way stop() must interrupt / terminate without us releasing the gate again.
        Thread.sleep(20);
        pipeline.stop();
        pipeline.stop(); // idempotent
        assertEquals(PipelineState.STOPPED, tel.state());
        assertEquals(1, src.stopCalls.get());
        assertEquals(1, proc.closed.get());
        assertTrue(pipeline.queue().isEmpty());
        assertFalse(pipeline.isRunning());
        awaitCondition(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.getName().equals("zs-processing") && t.isAlive()));
        assertEquals(2, src.recycled.get(), "both frames returned to the source");
        // Frames arriving after stop are recycled, not queued
        ManualSource late = src;
        late.listener = pipeline; // simulate a straggler callback from the camera thread
        late.emit(3);
        assertEquals(3, src.recycled.get());
        assertTrue(pipeline.queue().isEmpty());
    }

    @Test
    void sourceStartFailureYieldsUnavailableWithoutThrowing() {
        ManualSource src = new ManualSource();
        src.failOnStart = true;
        PipelineTelemetry tel = new PipelineTelemetry();
        pipeline = new FramePipeline(src, new DiagnosticFrameProcessor(), tel);
        pipeline.start();
        assertEquals(PipelineState.UNAVAILABLE, tel.state());
        assertTrue(tel.snapshot().stateDetail().contains("no rear camera"));
        pipeline.stop();
        assertEquals(PipelineState.STOPPED, tel.state());
    }

    @Test
    void sourceErrorCallbackMovesToUnavailable() {
        ManualSource src = new ManualSource();
        PipelineTelemetry tel = new PipelineTelemetry();
        pipeline = new FramePipeline(src, new DiagnosticFrameProcessor(), tel);
        pipeline.start();
        src.listener.onSourceError("camera disconnected", null);
        assertEquals(PipelineState.UNAVAILABLE, tel.state());
        assertEquals("camera disconnected", tel.snapshot().stateDetail());
    }

    @Test
    void endToEndWithSyntheticSourceAndDiagnosticProcessor() throws Exception {
        SyntheticFrameSource src = new SyntheticFrameSource(16, 8, 90, 200, 40, System::nanoTime,
                n -> Thread.sleep(1));
        DiagnosticFrameProcessor proc = new DiagnosticFrameProcessor();
        PipelineTelemetry tel = new PipelineTelemetry();
        pipeline = new FramePipeline(src, proc, tel);
        pipeline.start();
        awaitCondition(() -> tel.receivedFrames() == 40);
        awaitCondition(() -> tel.processedFrames() + tel.droppedOrReplacedFrames() >= 39);
        pipeline.stop();
        PipelineTelemetry.Snapshot s = tel.snapshot();
        assertEquals(40, s.receivedFrames());
        assertEquals(40, s.processedFrames() + s.droppedOrReplacedFrames() + (pipelineHadPendingAtStop(s) ? 1 : 0),
                "every received frame is either processed or accounted as replaced (or drained at stop)");
        assertEquals(16, s.frameWidth());
        assertEquals(8, s.frameHeight());
        assertEquals(90, s.rotationDegrees());
        assertEquals(90, proc.lastRotation());
        assertFalse(Double.isNaN(proc.lastMeanLuma()));
        assertTrue(s.receivedFps() > 0);
        assertTrue(proc.statusLine().contains("STAGE 2"));
    }

    private static boolean pipelineHadPendingAtStop(PipelineTelemetry.Snapshot s) {
        return s.processedFrames() + s.droppedOrReplacedFrames() == s.receivedFrames() - 1;
    }

    @Test
    void diagnosticProcessorComputesMeanLuma() {
        DiagnosticFrameProcessor p = new DiagnosticFrameProcessor();
        ByteBuffer b = ByteBuffer.allocate(Frame.packedSize(Frame.PixelFormat.NV21, 100, 100));
        for (int i = 0; i < 100 * 100; i++) b.put(i, (byte) 200);
        p.process(new Frame(100, 100, Frame.PixelFormat.NV21, b, 0, 1, Frame.CameraSource.TEST));
        assertEquals(200.0, p.lastMeanLuma(), 1e-9);
        assertEquals(100, p.lastWidth());
    }

    @Test
    void queueClearDoesNotCountAsDrop() {
        LatestFrameQueue q = new LatestFrameQueue();
        Frame f = new Frame(1, 1, Frame.PixelFormat.RGB_888, ByteBuffer.allocate(3), 0, 1, Frame.CameraSource.TEST);
        assertNull(q.offer(f));
        assertEquals(f, q.clear());
        assertEquals(0, q.droppedCount());
        assertEquals(1, q.offeredCount());
        AtomicReference<Frame> r = new AtomicReference<>(q.offer(f));
        assertNull(r.get());
        assertEquals(f, q.offer(f));
        assertEquals(1, q.droppedCount());
    }
}
