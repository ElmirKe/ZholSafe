package kz.zholsafe.trajectory;

import kz.zholsafe.config.TrajectoryConfig;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Estimate;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;
import kz.zholsafe.tracking.MovementClass;
import kz.zholsafe.tracking.TrackObservation;
import kz.zholsafe.tracking.TrackState;
import kz.zholsafe.tracking.TrackView;
import kz.zholsafe.tracking.TrackedObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinearImageTrajectoryEstimatorTest {
    private static final long SECOND = 1_000_000_000L;
    private static final TrajectoryConfig CONFIG = TrajectoryConfig.defaults();
    private final LinearImageTrajectoryEstimator estimator = new LinearImageTrajectoryEstimator(CONFIG);

    private static TrackObservation s(long ts, double cx, double cy, double width, double height) {
        return new TrackObservation(ts, new BoundingBox((float) (cx - width / 2), (float) (cy - height / 2),
                (float) (cx + width / 2), (float) (cy + height / 2)), .9f);
    }
    private static TrackView track(List<TrackObservation> history, TrackState state, int missed) {
        TrackObservation last = history.get(history.size() - 1);
        TrackedObject object = new TrackedObject(7, ObjectClass.HORSE, last.confidence(), last.box(),
                history.stream().map(TrackObservation::center).toList(), MovementClass.UNKNOWN,
                Estimate.unavailable(), Estimate.unavailable(), false, history.size(), last.timestampNanos());
        return new TrackView(object, state, history.size(), missed, history);
    }
    private static TrackingSnapshot snap(int w, int h, long ts, TrackView... tracks) {
        return new TrackingSnapshot(ts, w, h, TrackingSnapshot.Status.READY, List.of(tracks));
    }
    private ObjectTrajectory analyze(int w, int h, TrackObservation... samples) {
        long ts = samples[samples.length - 1].timestampNanos();
        TrajectorySnapshot result = estimator.estimate(snap(w, h, ts, track(List.of(samples), TrackState.CONFIRMED, 0)));
        assertTrue(result.available());
        return result.objects().get(0);
    }

    @Test void stationaryHasZeroVelocityAndStableScale() {
        ObjectTrajectory o = analyze(200, 100, s(SECOND, 100, 50, 20, 20),
                s(2 * SECOND, 100, 50, 20, 20), s(3 * SECOND, 100, 50, 20, 20));
        assertEquals(TrajectoryStatus.AVAILABLE, o.status());
        assertEquals(TrajectoryQuality.FIT_ACCEPTED, o.quality());
        assertEquals(0d, o.motion().normalizedSpeedPerSecond(), 1e-12);
        assertEquals(ImageDirection.STATIONARY, o.motion().direction());
        assertEquals(ScaleChange.STABLE, o.scale().change());
        assertEquals(ApproachState.LATERAL_OR_STABLE, o.approach());
        assertEquals(.02, o.scale().normalizedArea(), 1e-9);
        assertEquals(2d, o.timeSpanSeconds(), 1e-12);
        assertEquals(100f, o.centerPixels().x());
    }

    @Test void leftAndRightHaveSignedNormalizedVelocity() {
        ObjectTrajectory left = analyze(200, 100, s(SECOND, 80, 50, 20, 20),
                s(2 * SECOND, 70, 50, 20, 20), s(3 * SECOND, 60, 50, 20, 20));
        assertEquals(-.05, left.motion().velocityXFrameWidthsPerSecond(), 1e-9);
        assertEquals(ImageDirection.LEFT, left.motion().direction());
        assertEquals(0d, left.motion().velocityYFrameHeightsPerSecond(), 1e-12);
        ObjectTrajectory right = analyze(200, 100, s(SECOND, 60, 50, 20, 20),
                s(2 * SECOND, 70, 50, 20, 20), s(3 * SECOND, 80, 50, 20, 20));
        assertEquals(.05, right.motion().velocityXFrameWidthsPerSecond(), 1e-9);
        assertEquals(ImageDirection.RIGHT, right.motion().direction());
        assertEquals(ApproachState.LATERAL_OR_STABLE, right.approach());
    }

    @Test void imageYIncreasesDownwardAndDiagonalsUseBothAxes() {
        ObjectTrajectory up = analyze(200, 200, s(SECOND, 100, 80, 20, 20),
                s(2 * SECOND, 100, 70, 20, 20), s(3 * SECOND, 100, 60, 20, 20));
        assertEquals(ImageDirection.UP, up.motion().direction());
        assertEquals(-.05, up.motion().velocityYFrameHeightsPerSecond(), 1e-9);
        ObjectTrajectory down = analyze(200, 200, s(SECOND, 100, 60, 20, 20),
                s(2 * SECOND, 100, 70, 20, 20), s(3 * SECOND, 100, 80, 20, 20));
        assertEquals(ImageDirection.DOWN, down.motion().direction());
        assertEquals(.05, down.motion().velocityYFrameHeightsPerSecond(), 1e-9);
        ObjectTrajectory diag = analyze(200, 200, s(SECOND, 40, 80, 20, 20),
                s(2 * SECOND, 50, 70, 20, 20), s(3 * SECOND, 60, 60, 20, 20));
        assertEquals(ImageDirection.UP_RIGHT, diag.motion().direction());
        assertEquals(.05, diag.motion().velocityXFrameWidthsPerSecond(), 1e-9);
        assertEquals(-.05, diag.motion().velocityYFrameHeightsPerSecond(), 1e-9);
        assertEquals(Math.hypot(.05, .05), diag.motion().normalizedSpeedPerSecond(), 1e-9);
    }

    @Test void growthAndShrinkAreQualitativeImageEvidenceOnly() {
        ObjectTrajectory growing = analyze(200, 200, s(SECOND, 100, 100, 20, 20),
                s(2 * SECOND, 100, 100, 24, 24), s(3 * SECOND, 100, 100, 30, 30));
        assertEquals(ImageDirection.STATIONARY, growing.motion().direction());
        assertTrue(growing.scale().logAreaRatePerSecond() > CONFIG.approachGrowthThreshold());
        assertEquals(ScaleChange.GROWING, growing.scale().change());
        assertEquals(ApproachState.APPROACHING, growing.approach());
        ObjectTrajectory shrinking = analyze(200, 200, s(SECOND, 100, 100, 30, 30),
                s(2 * SECOND, 100, 100, 24, 24), s(3 * SECOND, 100, 100, 20, 20));
        assertTrue(shrinking.scale().logAreaRatePerSecond() < -CONFIG.recedeShrinkThreshold());
        assertEquals(ScaleChange.SHRINKING, shrinking.scale().change());
        assertEquals(ApproachState.RECEDING, shrinking.approach());
    }

    @Test void oneLateScaleJumpIsNotSustainedApproach() {
        ObjectTrajectory o = analyze(200, 200, s(SECOND, 100, 100, 20, 20),
                s(2 * SECOND, 100, 100, 20, 20), s(3 * SECOND, 100, 100, 24, 24));
        assertEquals(TrajectoryStatus.AVAILABLE, o.status());
        assertTrue(o.scale().logAreaRatePerSecond() > CONFIG.approachGrowthThreshold());
        assertEquals(ScaleChange.UNCERTAIN, o.scale().change());
        assertEquals(ApproachState.UNCERTAIN, o.approach());
    }

    @Test void smallAlternatingJitterCannotBecomeStrongMotionOrApproach() {
        ObjectTrajectory o = analyze(200, 200, s(SECOND, 100, 100, 20, 20),
                s(2 * SECOND, 101, 100, 21, 21), s(3 * SECOND, 100, 100, 20, 20),
                s(4 * SECOND, 101, 100, 21, 21));
        assertEquals(TrajectoryStatus.AVAILABLE, o.status());
        assertEquals(ImageDirection.STATIONARY, o.motion().direction());
        assertEquals(ApproachState.LATERAL_OR_STABLE, o.approach());
        ObjectTrajectory severe = analyze(200, 200, s(SECOND, 60, 100, 20, 20),
                s(2 * SECOND, 120, 100, 20, 20), s(3 * SECOND, 50, 100, 20, 20),
                s(4 * SECOND, 130, 100, 20, 20));
        assertEquals(TrajectoryStatus.LOW_QUALITY, severe.status());
        assertFalse(severe.motion().available());
        assertTrue(Double.isNaN(severe.motion().velocityXFrameWidthsPerSecond()));
    }

    @Test void insufficientHistoryOrShortTimeSpanIsExplicitlyUnavailable() {
        ObjectTrajectory one = analyze(200, 200, s(SECOND, 50, 50, 20, 20));
        assertEquals(TrajectoryStatus.INSUFFICIENT_HISTORY, one.status());
        ObjectTrajectory two = analyze(200, 200, s(SECOND, 50, 50, 20, 20),
                s(2 * SECOND, 60, 50, 20, 20));
        assertEquals(TrajectoryStatus.INSUFFICIENT_HISTORY, two.status());
        ObjectTrajectory shortSpan = analyze(200, 200, s(SECOND, 50, 50, 20, 20),
                s(SECOND + 1_000_000L, 50, 50, 20, 20), s(SECOND + 2_000_000L, 50, 50, 20, 20));
        assertEquals(TrajectoryStatus.LOW_QUALITY, shortSpan.status());
        assertFalse(shortSpan.scale().available());
    }

    @Test void duplicateAndOutOfOrderTimestampsAreInvalidNotFabricatedDt() {
        ObjectTrajectory duplicate = analyze(200, 200, s(SECOND, 50, 50, 20, 20),
                s(SECOND, 50, 50, 20, 20), s(2 * SECOND, 50, 50, 20, 20));
        assertEquals(TrajectoryStatus.INVALID_TIMESTAMPS, duplicate.status());
        ObjectTrajectory reversed = analyze(200, 200, s(2 * SECOND, 50, 50, 20, 20),
                s(SECOND, 50, 50, 20, 20), s(3 * SECOND, 50, 50, 20, 20));
        assertEquals(TrajectoryStatus.INVALID_TIMESTAMPS, reversed.status());
        assertTrue(Double.isNaN(reversed.timeSpanSeconds()));
    }

    @Test void irregularIntervalsUseSourceTimeNotFrameCount() {
        ObjectTrajectory o = analyze(200, 200, s(SECOND, 50, 100, 20, 20),
                s(SECOND + 200_000_000L, 54, 100, 20, 20),
                s(SECOND + 1_500_000_000L, 80, 100, 20, 20));
        assertEquals(TrajectoryStatus.AVAILABLE, o.status());
        assertEquals(.1, o.motion().velocityXFrameWidthsPerSecond(), 1e-9);
        assertEquals(1.5, o.timeSpanSeconds(), 1e-9);
        assertEquals(ImageDirection.RIGHT, o.motion().direction());
    }

    @Test void largeMonotonicTimestampOriginDoesNotLoseSubsecondPrecision() {
        long origin = Long.MAX_VALUE - 3 * SECOND;
        ObjectTrajectory o = analyze(200, 200, s(origin, 50, 100, 20, 20),
                s(origin + 200_000_000L, 54, 100, 20, 20),
                s(origin + 1_500_000_000L, 80, 100, 20, 20));
        assertEquals(TrajectoryStatus.AVAILABLE, o.status());
        assertEquals(.1, o.motion().velocityXFrameWidthsPerSecond(), 1e-9);
    }

    @Test void normalizationGivesEquivalentResultsAtTwoResolutions() {
        ObjectTrajectory small = analyze(100, 100, s(SECOND, 30, 50, 10, 10),
                s(2 * SECOND, 40, 50, 10, 10), s(3 * SECOND, 50, 50, 10, 10));
        ObjectTrajectory large = analyze(1000, 1000, s(SECOND, 300, 500, 100, 100),
                s(2 * SECOND, 400, 500, 100, 100), s(3 * SECOND, 500, 500, 100, 100));
        assertEquals(.1, small.motion().velocityXFrameWidthsPerSecond(), 1e-9);
        assertEquals(small.motion().velocityXFrameWidthsPerSecond(), large.motion().velocityXFrameWidthsPerSecond(), 1e-9);
        assertEquals(small.scale().normalizedArea(), large.scale().normalizedArea(), 1e-9);
    }

    @Test void lostAndTentativeTracksNeverYieldCurrentTrajectories() {
        List<TrackObservation> history = List.of(s(SECOND, 60, 60, 20, 20),
                s(2 * SECOND, 70, 60, 20, 20), s(3 * SECOND, 80, 60, 20, 20));
        TrackView lost = track(history, TrackState.LOST, 1);
        TrajectorySnapshot result = estimator.estimate(snap(200, 200, 4 * SECOND, lost));
        assertEquals(TrajectorySnapshot.Status.READY, result.status());
        assertEquals(0, result.availableCount());
        assertEquals(TrajectoryStatus.TRACK_NOT_CURRENT, result.objects().get(0).status());
        assertTrue(Double.isNaN(result.objects().get(0).scale().normalizedArea()));
        assertEquals(TrajectoryStatus.TRACK_NOT_CURRENT,
                estimator.estimate(snap(200, 200, 3 * SECOND, track(history, TrackState.TENTATIVE, 0)))
                        .objects().get(0).status());
    }

    @Test void onlyConfiguredRecentSamplesAreUsedEvenWithLongHistory() {
        TrajectoryConfig c = new TrajectoryConfig(3, 4, .25, .012, .35, .03, .12, .12, .03, .12);
        List<TrackObservation> samples = new ArrayList<>();
        for (int i = 1; i <= 12; i++) samples.add(s(i * SECOND, i < 9 ? 20 + i * 10 : 100, 50, 20, 20));
        ObjectTrajectory result = new LinearImageTrajectoryEstimator(c)
                .estimate(snap(200, 200, 12 * SECOND, track(samples, TrackState.CONFIRMED, 0))).objects().get(0);
        assertEquals(4, result.sampleCount());
        assertEquals(TrajectoryStatus.AVAILABLE, result.status());
        assertEquals(ImageDirection.STATIONARY, result.motion().direction());
        assertEquals(3d, result.timeSpanSeconds(), 1e-9);
    }

    @Test void intermediateScaleRateIsUncertainRatherThanAnApproachClaim() {
        ObjectTrajectory o = analyze(200, 200, s(SECOND, 100, 100, 40, 40),
                s(2 * SECOND, 100, 100, 41, 41), s(3 * SECOND, 100, 100, 42, 42));
        assertEquals(TrajectoryStatus.AVAILABLE, o.status());
        assertEquals(ScaleChange.UNCERTAIN, o.scale().change());
        assertEquals(ApproachState.UNCERTAIN, o.approach());
    }

    @Test void invalidImageBoxesAreNotPhysicalMotionAndNoPhysicalEstimatorWasChanged() {
        ObjectTrajectory outside = analyze(200, 200, s(SECOND, 50, 50, 20, 20),
                s(2 * SECOND, 50, 50, 20, 20), s(3 * SECOND, 200, 50, 20, 20));
        assertEquals(TrajectoryStatus.LOW_QUALITY, outside.status());
        assertFalse(outside.motion().available());
        assertFalse(outside.scale().available());
        for (var component : ObjectTrajectory.class.getRecordComponents()) {
            assertFalse(component.getType() == Estimate.class, "no physical Estimate in image trajectory");
        }
        TrackedObject base = track(List.of(s(SECOND, 60, 60, 20, 20)), TrackState.CONFIRMED, 0).object();
        assertFalse(base.estimatedDistance().available());
        assertFalse(base.estimatedTtc().available());
        assertEquals(MovementClass.UNKNOWN, estimator.classify(base, 200, 200),
                "legacy timestamp-free classifier cannot assert physical or image movement");
    }

    @Test void snapshotAndNumericAvailabilityContractsAreImmutable() {
        List<TrackObservation> history = List.of(s(SECOND, 50, 50, 20, 20),
                s(2 * SECOND, 50, 50, 20, 20), s(3 * SECOND, 50, 50, 20, 20));
        ArrayList<ObjectTrajectory> mutable = new ArrayList<>(estimator.estimate(
                snap(200, 200, 3 * SECOND, track(history, TrackState.CONFIRMED, 0))).objects());
        TrajectorySnapshot snap = new TrajectorySnapshot(3 * SECOND, 200, 200,
                TrajectorySnapshot.Status.READY, TrackingSnapshot.Status.READY, mutable);
        mutable.clear();
        assertEquals(1, snap.objects().size());
        assertThrows(UnsupportedOperationException.class, () -> snap.objects().clear());
        assertThrows(IllegalArgumentException.class, () -> new TrajectorySnapshot(3 * SECOND, 0, 200,
                TrajectorySnapshot.Status.READY, TrackingSnapshot.Status.READY, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new TrajectorySnapshot(3 * SECOND, 0, 0,
                TrajectorySnapshot.Status.TRACKING_UNAVAILABLE, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE,
                snap.objects()));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageMotion(false, 0d, 0d, 0d, ImageDirection.UNCERTAIN));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageMotion(true, 1d, 0d, 0d, ImageDirection.RIGHT));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageScaleTrend(false, 0d, 0d, ScaleChange.UNCERTAIN));
    }

    @Test void upstreamFailureIsUnavailableNotReadyEmpty() {
        TrackingSnapshot failure = TrackingSnapshot.unavailable(4 * SECOND, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE);
        TrajectorySnapshot result = estimator.estimate(failure);
        assertFalse(result.available());
        assertEquals(TrajectorySnapshot.Status.TRACKING_UNAVAILABLE, result.status());
        assertEquals(TrackingSnapshot.Status.DETECTOR_UNAVAILABLE, result.upstreamStatus());
        assertTrue(result.objects().isEmpty());
        assertTrue(estimator.estimate(snap(200, 200, SECOND)).available());
        assertTrue(estimator.estimate(snap(200, 200, SECOND)).objects().isEmpty());
    }
}
