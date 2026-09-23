package kz.zholsafe.pipeline;

import kz.zholsafe.ai.DetectionException;
import kz.zholsafe.ai.DetectorState;
import kz.zholsafe.ai.FakeRoadDetector;
import kz.zholsafe.ai.Frame;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.physical.PhysicalEstimationSnapshot;
import kz.zholsafe.physical.PhysicalReason;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadDetectionProcessorTest {

    static Frame frame(long ts) {
        return new Frame(8, 4, Frame.PixelFormat.NV21, ByteBuffer.allocate(48), 90, ts, Frame.CameraSource.TEST);
    }

    @Test
    void publishesSnapshotWithCountsAndUprightDimensions() throws Exception {
        FakeRoadDetector det = new FakeRoadDetector(FakeRoadDetector.centeredPerson(0.8f));
        AtomicLong clock = new AtomicLong();
        RoadDetectionProcessor p = new RoadDetectionProcessor(det, () -> clock.addAndGet(5_000_000L));
        assertFalse(p.latest().available(), "before load: unavailable");
        assertTrue(p.load());
        p.process(frame(42));
        DetectionSnapshot s = p.latest();
        assertTrue(s.available());
        assertEquals(42L, s.frameTimestampNanos());
        assertEquals(4, s.uprightWidth());
        assertEquals(8, s.uprightHeight());
        assertEquals(1, s.detections().size());
        assertEquals(1, s.countByClass().get(ObjectClass.PERSON));
        assertEquals(0, s.countByClass().get(ObjectClass.COW));
        assertEquals(1, s.sequence());
        assertEquals(1, p.inferenceCount());
        assertEquals(5.0, p.recentTotalMillis(), 1e-9);
        assertEquals(PhysicalEstimationSnapshot.Status.READY, p.latestPhysical().status());
        assertTrue(p.statusLine().contains("READY"));
    }

    @Test
    void noDetectionsIsAvailableButEmpty() throws Exception {
        RoadDetectionProcessor p = new RoadDetectionProcessor(new FakeRoadDetector());
        p.load();
        p.process(frame(1));
        assertTrue(p.latest().available());
        assertTrue(p.latest().detections().isEmpty());
        assertTrue(p.latestPhysical().available());
        assertTrue(p.latestPhysical().objects().isEmpty(), "empty successful road != unavailable");
    }

    @Test
    void loadFailureMakesEveryFrameUnavailableAndThrows() {
        FakeRoadDetector det = new FakeRoadDetector();
        det.setFailLoad(true);
        RoadDetectionProcessor p = new RoadDetectionProcessor(det);
        assertFalse(p.load());
        assertThrows(DetectionException.class, () -> p.process(frame(1)));
        assertFalse(p.latest().available());
        assertEquals(DetectorState.ERROR, p.latest().detectorState());
        assertTrue(p.statusLine().contains("UNAVAILABLE"));
        assertEquals(0, p.inferenceCount());
        assertEquals(PhysicalEstimationSnapshot.Status.TRACKING_UNAVAILABLE, p.latestPhysical().status());
        assertTrue(p.latestPhysical().objects().isEmpty());
    }

    @Test
    void perFrameFailureIsRethrownAndSnapshotBecomesUnavailableNotEmpty() throws Exception {
        FakeRoadDetector det = new FakeRoadDetector(FakeRoadDetector.centeredPerson(0.9f));
        RoadDetectionProcessor p = new RoadDetectionProcessor(det);
        p.load();
        p.process(frame(1));
        assertTrue(p.latest().available());
        det.setThrowOnDetect(new IllegalStateException("boom"));
        assertThrows(DetectionException.class, () -> p.process(frame(2)));
        assertFalse(p.latest().available(), "failure must not look like an empty (clear) road");
        assertEquals(1, p.failureCount());
        assertEquals(PhysicalEstimationSnapshot.Status.TRACKING_UNAVAILABLE, p.latestPhysical().status());
        det.setThrowOnDetect(null);
        p.process(frame(3));
        assertTrue(p.latest().available());
        assertTrue(p.latestPhysical().available());
    }

    @Test
    void closeClosesDetector() {
        FakeRoadDetector det = new FakeRoadDetector();
        RoadDetectionProcessor p = new RoadDetectionProcessor(det);
        p.close();
        assertEquals(1, det.closeCalls());
        assertEquals(DetectorState.CLOSED, det.state());
    }
}
