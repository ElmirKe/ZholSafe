package kz.zholsafe.benchmark;

import kz.zholsafe.ai.DetectionException;
import kz.zholsafe.ai.FakeRoadDetector;
import kz.zholsafe.ai.Frame;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectorBenchmarkTest {

    @Test
    void statsPercentilesAndFps() throws Exception {
        FakeRoadDetector det = new FakeRoadDetector(FakeRoadDetector.centeredPerson(0.5f));
        det.load();
        AtomicLong clock = new AtomicLong();
        // each clock read advances 1 ms → each detect() costs exactly 1 ms
        DetectorBenchmark b = new DetectorBenchmark(() -> clock.addAndGet(1_000_000L));
        Frame f = new Frame(4, 4, Frame.PixelFormat.NV21, ByteBuffer.allocate(24), 0, 1, Frame.CameraSource.TEST);
        BenchmarkResult r = b.run(det, DetectorBenchmark.repeat(f, 20), 5, "jvm-test", EvaluationCategory.LATENCY_ONLY);
        assertEquals(20, r.measuredFrames());
        assertEquals(5, r.warmUpFrames());
        assertEquals(20, r.totalDetections());
        assertEquals(1.0, r.total().meanMs(), 1e-9);
        assertEquals(1.0, r.total().p95Ms(), 1e-9);
        assertTrue(r.fps() > 0);
        assertTrue(r.toReportLine().contains("model=fake-road-detector"));
    }

    @Test
    void refusesNotReadyDetector() {
        FakeRoadDetector det = new FakeRoadDetector();
        Frame f = new Frame(4, 4, Frame.PixelFormat.NV21, ByteBuffer.allocate(24), 0, 1, Frame.CameraSource.TEST);
        assertThrows(DetectionException.class, () -> new DetectorBenchmark().run(det, List.of(f), 0, "x", EvaluationCategory.DAY));
    }

    @Test
    void percentileNearestRank() {
        long[] s = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 };
        assertEquals(5, DetectorBenchmark.Stats.percentile(s, 50));
        assertEquals(10, DetectorBenchmark.Stats.percentile(s, 95));
        assertTrue(Double.isNaN(DetectorBenchmark.Stats.of(new long[0]).meanMs()));
    }

    @Test
    void accuracyMatchingIsClassAwareGreedy() {
        List<AccuracyResult.GroundTruth> gt = List.of(
                new AccuracyResult.GroundTruth(ObjectClass.COW, new BoundingBox(0, 0, 100, 100)),
                new AccuracyResult.GroundTruth(ObjectClass.PERSON, new BoundingBox(200, 200, 250, 300)));
        List<Detection> preds = List.of(
                new Detection(0, ObjectClass.COW, 0.9f, new BoundingBox(5, 5, 100, 100), 0),     // TP
                new Detection(0, ObjectClass.COW, 0.8f, new BoundingBox(2, 2, 100, 100), 0),     // duplicate → FP
                new Detection(0, ObjectClass.SHEEP, 0.9f, new BoundingBox(200, 200, 250, 300), 0)); // wrong class → FP, person FN
        AccuracyResult r = AccuracyResult.evaluate(preds, gt, 0.5);
        assertEquals(1, r.tp());
        assertEquals(2, r.fp());
        assertEquals(1, r.fn());
        assertEquals(1.0 / 3, r.precision(), 1e-9);
        assertEquals(0.5, r.recall(), 1e-9);
    }
}
