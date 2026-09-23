package kz.zholsafe.pipeline;

import kz.zholsafe.ai.FakeRoadDetector;
import kz.zholsafe.ai.Frame;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SyntheticFrameSource → FramePipeline → RoadDetectionProcessor(FakeRoadDetector) → DetectionSnapshot. */
class DetectionPipelineIntegrationTest {

    private static void await(java.util.function.BooleanSupplier c) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timeout");
            Thread.sleep(2);
        }
    }

    @Test
    void endToEndProducesSnapshotsAndShutsDownCleanly() throws Exception {
        SyntheticFrameSource src = new SyntheticFrameSource(32, 16, 90, 500, 60, System::nanoTime, n -> Thread.sleep(1));
        AtomicReference<WeakReference<Frame>> lastFrameRef = new AtomicReference<>();
        FakeRoadDetector det = new FakeRoadDetector(f -> {
            lastFrameRef.set(new WeakReference<>(f));
            return FakeRoadDetector.centeredPerson(0.75f).apply(f);
        });
        RoadDetectionProcessor proc = new RoadDetectionProcessor(det);
        assertTrue(proc.load());
        PipelineTelemetry tel = new PipelineTelemetry();
        FramePipeline pipeline = new FramePipeline(src, proc, tel);
        pipeline.start();
        await(() -> tel.receivedFrames() == 60);
        await(() -> tel.processedFrames() + tel.droppedOrReplacedFrames() >= 59);
        pipeline.stop();

        DetectionSnapshot s = proc.latest();
        assertTrue(s.available());
        assertEquals(16, s.uprightWidth(), "boxes are in upright coordinates (32×16 stored, rotated 90)");
        assertEquals(32, s.uprightHeight());
        Detection d = s.detections().get(0);
        assertTrue(d.box().x2() <= 16 && d.box().y2() <= 32);
        assertEquals(ObjectClass.PERSON, d.objectClass());
        assertEquals(0, tel.processingErrors());
        assertEquals(60, tel.receivedFrames());
        assertTrue(pipeline.queue().isEmpty(), "no accumulation");
        assertEquals(1, det.closeCalls(), "detector closed on shutdown");
        assertEquals(PipelineState.STOPPED, tel.state());
        // Snapshot holds only numbers; frame buffers are not retained by the processor.
        assertFalse(s.toString().contains("HeapByteBuffer"));
        System.gc();
        Thread.sleep(20);
        System.gc();
        assertNull(lastFrameRef.get().get(), "processor/snapshot must not retain the Frame");
    }

    @Test
    void detectorExceptionsDegradeButPipelineContinues() throws Exception {
        SyntheticFrameSource src = new SyntheticFrameSource(8, 8, 0, 500, 0, System::nanoTime, n -> Thread.sleep(1));
        FakeRoadDetector det = new FakeRoadDetector();
        det.setThrowOnDetect(new IllegalStateException("ort failure"));
        RoadDetectionProcessor proc = new RoadDetectionProcessor(det);
        proc.load();
        PipelineTelemetry tel = new PipelineTelemetry();
        FramePipeline pipeline = new FramePipeline(src, proc, tel);
        pipeline.start();
        await(() -> tel.processingErrors() >= 3);
        assertEquals(PipelineState.DEGRADED, tel.state());
        assertFalse(proc.latest().available());
        det.setThrowOnDetect(null);
        await(() -> tel.processedFrames() >= 1);
        await(() -> tel.state() == PipelineState.RUNNING);
        assertTrue(proc.latest().available());
        pipeline.stop();
        assertTrue(pipeline.isRunning() == false);
    }

    @Test
    void modelNotAvailableNeverFabricatesDetections() throws Exception {
        SyntheticFrameSource src = new SyntheticFrameSource(8, 8, 0, 500, 5, System::nanoTime, n -> Thread.sleep(1));
        FakeRoadDetector det = new FakeRoadDetector(FakeRoadDetector.centeredPerson(0.9f));
        det.setFailLoad(true);
        RoadDetectionProcessor proc = new RoadDetectionProcessor(det);
        assertFalse(proc.load());
        PipelineTelemetry tel = new PipelineTelemetry();
        FramePipeline pipeline = new FramePipeline(src, proc, tel);
        pipeline.start();
        await(() -> tel.receivedFrames() == 5);
        await(() -> tel.processingErrors() + tel.droppedOrReplacedFrames() >= 4);
        pipeline.stop();
        assertEquals(0, tel.processedFrames());
        assertFalse(proc.latest().available());
        assertEquals(List.of(), proc.latest().detections());
        assertEquals(PipelineState.STOPPED, tel.state());
    }
}
