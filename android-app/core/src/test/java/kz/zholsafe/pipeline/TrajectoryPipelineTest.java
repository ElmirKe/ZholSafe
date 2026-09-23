package kz.zholsafe.pipeline;

import kz.zholsafe.ai.DetectionException;
import kz.zholsafe.ai.FakeRoadDetector;
import kz.zholsafe.ai.Frame;
import kz.zholsafe.config.TrackingConfig;
import kz.zholsafe.config.TrajectoryConfig;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.trajectory.ApproachState;
import kz.zholsafe.trajectory.ImageDirection;
import kz.zholsafe.trajectory.TrajectoryStatus;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TrajectoryPipelineTest {
    private static final long S = 1_000_000_000L;
    private static Frame frame(long ts) {
        return new Frame(100, 100, Frame.PixelFormat.NV21,
                ByteBuffer.allocate(Frame.packedSize(Frame.PixelFormat.NV21, 100, 100)),
                0, ts, Frame.CameraSource.TEST);
    }
    private static TrackingConfig tracking() {
        return new TrackingConfig(.3f, 2, 2, 8, .3f, .7f, .4f, .6f, .3f, 32);
    }

    @Test void sameProcessingPathProducesImageTrajectoryAfterConfirmationAndThreeSamples() throws Exception {
        FakeRoadDetector detector = new FakeRoadDetector(f -> List.of(new Detection(17, ObjectClass.HORSE, .9f,
                new BoundingBox(40, 40, 60, 60), f.timestampNanos())));
        // Processing clock is deliberately unrelated to source timestamps.
        AtomicLong processingClock = new AtomicLong(9_000_000_000_000_000L);
        RoadDetectionProcessor p = new RoadDetectionProcessor(detector,
                () -> processingClock.addAndGet(5_000_000L), tracking(), TrajectoryConfig.defaults());
        assertEquals(TrajectorySnapshot.Status.NOT_STARTED, p.latestTrajectory().status());
        assertTrue(p.load());
        p.process(frame(S));
        assertEquals(TrajectoryStatus.TRACK_NOT_CURRENT, p.latestTrajectory().objects().get(0).status());
        p.process(frame(2 * S));
        assertEquals(TrajectoryStatus.INSUFFICIENT_HISTORY, p.latestTrajectory().objects().get(0).status());
        p.process(frame(3 * S));
        assertTrue(p.latestTrajectory().available());
        assertEquals(1, p.latestTrajectory().availableCount());
        var item = p.latestTrajectory().objects().get(0);
        assertEquals(p.latestTracking().tracks().get(0).object().trackId(), item.trackId());
        assertEquals(3 * S, item.timestampNanos());
        assertEquals(ImageDirection.STATIONARY, item.motion().direction());
        assertEquals(ApproachState.LATERAL_OR_STABLE, item.approach());
        assertFalse(p.latestTracking().tracks().get(0).object().estimatedDistance().available());
        assertFalse(p.latestTracking().tracks().get(0).object().estimatedTtc().available());
        assertTrue(DetectionReport.render(p).contains("IMAGE TRAJECTORIES (normalized only): 1/1 available"));
        assertFalse(DetectionReport.render(p).contains("m/s"));
    }

    @Test void realTrackerAssociationFeedsRightwardImageFitWithoutAnotherPipeline() throws Exception {
        FakeRoadDetector detector = new FakeRoadDetector(f -> {
            float shift = 2f * (f.timestampNanos() / S);
            return List.of(new Detection(17, ObjectClass.HORSE, .9f,
                    new BoundingBox(30 + shift, 40, 50 + shift, 60), f.timestampNanos()));
        });
        RoadDetectionProcessor p = new RoadDetectionProcessor(detector, tracking(), TrajectoryConfig.defaults());
        p.load();
        for (int i = 1; i <= 4; i++) p.process(frame(i * S));
        assertEquals(1, p.latestTracking().tracks().size());
        assertEquals(1, p.latestTrajectory().objects().get(0).trackId());
        assertEquals(.02, p.latestTrajectory().objects().get(0).motion().velocityXFrameWidthsPerSecond(), 1e-8);
        assertEquals(ImageDirection.RIGHT, p.latestTrajectory().objects().get(0).motion().direction());
    }

    @Test void successfulEmptyCreatesLostButDetectorFailureDoesNotTurnIntoEmptyTrajectory() throws Exception {
        AtomicBoolean present = new AtomicBoolean(true);
        FakeRoadDetector d = new FakeRoadDetector(f -> present.get() ? List.of(new Detection(17,
                ObjectClass.HORSE, .9f, new BoundingBox(40, 40, 60, 60), f.timestampNanos())) : List.of());
        RoadDetectionProcessor p = new RoadDetectionProcessor(d, tracking(), TrajectoryConfig.defaults());
        p.load();
        for (int i = 1; i <= 3; i++) p.process(frame(i * S));
        present.set(false);
        p.process(frame(4 * S));
        assertTrue(p.latestTrajectory().available());
        assertEquals(TrajectoryStatus.TRACK_NOT_CURRENT, p.latestTrajectory().objects().get(0).status());
        d.setThrowOnDetect(new IllegalStateException("unavailable"));
        assertThrows(DetectionException.class, () -> p.process(frame(5 * S)));
        assertEquals(TrajectorySnapshot.Status.TRACKING_UNAVAILABLE, p.latestTrajectory().status());
        assertEquals(TrackingSnapshot.Status.DETECTOR_UNAVAILABLE, p.latestTrajectory().upstreamStatus());
        assertFalse(p.latestTrajectory().available());
        assertTrue(p.latestTrajectory().objects().isEmpty());
        assertTrue(DetectionReport.render(p).contains("IMAGE TRAJECTORY UNAVAILABLE"));
        d.setThrowOnDetect(null);
        present.set(true);
        p.process(frame(6 * S));
        assertEquals(1, p.latestTrajectory().objects().get(0).trackId());
        assertTrue(p.latestTrajectory().objects().get(0).available());
    }

    @Test void trackerErrorPropagatesUnavailableAndEstimatorErrorDoesNotRewriteSuccessfulTracking() throws Exception {
        FakeRoadDetector d = new FakeRoadDetector(FakeRoadDetector.centeredPerson(.9f));
        RoadDetectionProcessor p = new RoadDetectionProcessor(d, tracking(), TrajectoryConfig.defaults());
        p.load();
        p.process(frame(2 * S));
        assertThrows(IllegalArgumentException.class, () -> p.process(frame(S)));
        assertTrue(p.latest().available());
        assertEquals(TrackingSnapshot.Status.TRACKER_ERROR, p.latestTracking().status());
        assertEquals(TrajectorySnapshot.Status.TRACKING_UNAVAILABLE, p.latestTrajectory().status());
        assertEquals(TrackingSnapshot.Status.TRACKER_ERROR, p.latestTrajectory().upstreamStatus());

        FakeRoadDetector d2 = new FakeRoadDetector(FakeRoadDetector.centeredPerson(.9f));
        RoadDetectionProcessor bad = new RoadDetectionProcessor(d2, System::nanoTime, tracking(),
                snapshot -> { throw new IllegalStateException("fit failed"); });
        bad.load();
        assertThrows(IllegalStateException.class, () -> bad.process(frame(S)));
        assertTrue(bad.latest().available());
        assertTrue(bad.latestTracking().available());
        assertEquals(TrajectorySnapshot.Status.ESTIMATOR_ERROR, bad.latestTrajectory().status());
        assertFalse(bad.latestTrajectory().available());
        assertTrue(DetectionReport.render(bad).contains("IMAGE TRAJECTORY UNAVAILABLE (ESTIMATOR_ERROR)"));
    }

    @Test void missingModelNeverPublishesReadyTrajectory() {
        FakeRoadDetector d = new FakeRoadDetector();
        d.setFailLoad(true);
        RoadDetectionProcessor p = new RoadDetectionProcessor(d, tracking(), TrajectoryConfig.defaults());
        assertFalse(p.load());
        assertEquals(TrajectorySnapshot.Status.TRACKING_UNAVAILABLE, p.latestTrajectory().status());
        assertThrows(DetectionException.class, () -> p.process(frame(S)));
        assertFalse(p.latestTrajectory().available());
    }
}
