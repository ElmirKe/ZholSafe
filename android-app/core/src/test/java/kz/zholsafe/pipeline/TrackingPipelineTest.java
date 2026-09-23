package kz.zholsafe.pipeline;

import kz.zholsafe.ai.DetectionException;
import kz.zholsafe.ai.FakeRoadDetector;
import kz.zholsafe.ai.Frame;
import kz.zholsafe.config.TrackingConfig;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.tracking.TrackState;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TrackingPipelineTest {
    static Frame frame(long ts) {
        return new Frame(64, 64, Frame.PixelFormat.NV21, ByteBuffer.allocate(Frame.packedSize(Frame.PixelFormat.NV21, 64, 64)),
                0, ts, Frame.CameraSource.TEST);
    }
    static TrackingConfig config() {
        return new TrackingConfig(.3f, 2, 2, 4, .3f, .7f, .4f, .6f, .3f, 32);
    }

    @Test void successfulEmptyIsMissButFailureFreezesTracksAndDoesNotLookClear() throws Exception {
        AtomicBoolean found = new AtomicBoolean(true);
        FakeRoadDetector det = new FakeRoadDetector(f -> found.get()
                ? List.of(new Detection(17, ObjectClass.HORSE, .9f, new BoundingBox(10, 10, 30, 30), f.timestampNanos()))
                : List.of());
        RoadDetectionProcessor p = new RoadDetectionProcessor(det, config());
        assertEquals(TrackingSnapshot.Status.NOT_STARTED, p.latestTracking().status());
        assertTrue(p.load());
        p.process(frame(1));
        p.process(frame(2));
        assertEquals(1, p.latestTracking().confirmedCount());
        assertEquals(1, p.latestTracking().confirmedObjects().size());
        assertThrows(UnsupportedOperationException.class, () -> p.latestTracking().tracks().clear());
        found.set(false);
        p.process(frame(3));
        assertTrue(p.latest().available());
        assertTrue(p.latest().detections().isEmpty());
        assertTrue(p.latestTracking().available());
        assertEquals(1, p.latestTracking().lostCount());
        assertTrue(p.latestTracking().confirmedObjects().isEmpty(), "lost box is not live risk input");
        assertTrue(DetectionReport.render(p).contains("NO DETECTIONS"));

        det.setThrowOnDetect(new IllegalStateException("inference unavailable"));
        assertThrows(DetectionException.class, () -> p.process(frame(4)));
        assertFalse(p.latest().available());
        assertEquals(TrackingSnapshot.Status.DETECTOR_UNAVAILABLE, p.latestTracking().status());
        assertEquals(4, p.latestTracking().frameTimestampNanos());
        assertTrue(p.latestTracking().tracks().isEmpty());
        assertTrue(DetectionReport.render(p).contains("TRACKING UNAVAILABLE"));
        det.setThrowOnDetect(null);
        found.set(true);
        p.process(frame(5));
        assertEquals(1, p.latestTracking().confirmedCount());
        assertEquals(1, p.latestTracking().tracks().get(0).object().trackId());
        assertEquals(0, p.latestTracking().tracks().get(0).missedFrames());
        assertEquals(3, p.latestTracking().tracks().get(0).hits());
    }

    @Test void snapshotDefensivelyCopiesInputAndRejectsUnavailableTracks() throws Exception {
        RoadDetectionProcessor p = new RoadDetectionProcessor(
                new FakeRoadDetector(FakeRoadDetector.centeredPerson(.9f)), config());
        p.load();
        p.process(frame(1));
        ArrayList<kz.zholsafe.tracking.TrackView> mutable = new ArrayList<>(p.latestTracking().tracks());
        TrackingSnapshot copy = new TrackingSnapshot(1, 64, 64, TrackingSnapshot.Status.READY, mutable);
        mutable.clear();
        assertEquals(1, copy.tracks().size());
        assertThrows(UnsupportedOperationException.class, () -> copy.tracks().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingSnapshot(1, 64, 64, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE, copy.tracks()));
    }

    @Test void loadFailureDistinguishesUnavailableFromEmptyAndNoTracksCreated() {
        FakeRoadDetector det = new FakeRoadDetector();
        det.setFailLoad(true);
        RoadDetectionProcessor p = new RoadDetectionProcessor(det, config());
        assertFalse(p.load());
        assertEquals(TrackingSnapshot.Status.DETECTOR_UNAVAILABLE, p.latestTracking().status());
        assertThrows(DetectionException.class, () -> p.process(frame(1)));
        assertFalse(p.latest().available());
        assertFalse(p.latestTracking().available());
    }

    @Test void outOfOrderTimestampIsTrackingErrorNotAFalseEmptyRoad() throws Exception {
        FakeRoadDetector det = new FakeRoadDetector(FakeRoadDetector.centeredPerson(.9f));
        RoadDetectionProcessor p = new RoadDetectionProcessor(det, config());
        p.load();
        p.process(frame(2));
        assertThrows(IllegalArgumentException.class, () -> p.process(frame(1)));
        assertTrue(p.latest().available(), "detector really ran on the bad-timestamp frame");
        assertEquals(TrackingSnapshot.Status.TRACKER_ERROR, p.latestTracking().status());
        p.process(frame(3));
        assertEquals(1, p.latestTracking().tracks().get(0).object().trackId());
        assertEquals(TrackState.CONFIRMED, p.latestTracking().tracks().get(0).state());
    }
}
