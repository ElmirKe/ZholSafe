package kz.zholsafe.physical;

import kz.zholsafe.config.PhysicalEstimationConfig;
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
import kz.zholsafe.trajectory.LinearImageTrajectoryEstimator;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PhysicalEstimationTest {
    private static final long S = 1_000_000_000L;
    private static final PhysicalEstimationConfig C = PhysicalEstimationConfig.defaults();
    private static final CameraCalibration CAL = CameraCalibration.measured(1280, 720, 700, 700,
            640, 360, 1.5, 0);
    private static final ObjectSizePrior SYNTHETIC = new ObjectSizePrior(ObjectClass.PERSON,
            SizeDimension.HEIGHT, 1.6, 1.7, 1.8, PriorSource.MEASURED_FOR_OBJECT);
    private final LinearImageTrajectoryEstimator trajectories = new LinearImageTrajectoryEstimator(TrajectoryConfig.defaults());

    private static TrackObservation observation(long ts, double depth) {
        // Synthetic upright full-height target (1.7m) touching flat ground, known pinhole camera.
        float halfWidth = (float) (245d / depth);
        float bottom = (float) (360d + 1050d / depth);
        float top = (float) (bottom - 1190d / depth);
        return new TrackObservation(ts, new BoundingBox(640 - halfWidth, top, 640 + halfWidth, bottom), .9f);
    }

    private static TrackView track(int id, ObjectClass cls, TrackState state, int missed,
                                   TrackObservation... observations) {
        List<TrackObservation> history = List.of(observations);
        TrackObservation last = history.get(history.size() - 1);
        TrackedObject object = new TrackedObject(id, cls, last.confidence(), last.box(),
                history.stream().map(TrackObservation::center).toList(), MovementClass.UNKNOWN,
                Estimate.unavailable(), Estimate.unavailable(), false, history.size(), last.timestampNanos());
        return new TrackView(object, state, history.size(), missed, history);
    }

    private static TrackingSnapshot snap(long ts, TrackView... tracks) {
        return new TrackingSnapshot(ts, 1280, 720, TrackingSnapshot.Status.READY, List.of(tracks));
    }

    private PhysicalEstimationSnapshot run(PhysicalEstimationProcessor p, TrackingSnapshot tracking) {
        return p.analyze(tracking, trajectories.estimate(tracking));
    }

    private static MetricDistanceSample sample(int id, long ts, double distance) {
        return new MetricDistanceSample(id, ts, distance, EvidenceQuality.MEDIUM, DistanceMethod.GROUND_PLANE);
    }

    @Test void analyticPinholeRayPlaneDepthPitchAndResolution() {
        CameraRay ray = CAL.ray(710, 430);
        assertEquals(.1, ray.x(), 1e-12);
        assertEquals(.1, ray.y(), 1e-12);
        GroundIntersection hit = GroundPlaneGeometry.intersect(CAL, ray, C.horizonRayMargin()).orElseThrow();
        assertEquals(15d, hit.opticalDepthMeters(), 1e-10);
        assertEquals(1.5, hit.groundRightMeters(), 1e-10);
        assertEquals(15d, hit.groundForwardMeters(), 1e-10);
        double pitch = Math.atan(.1);
        CameraCalibration pitched = CameraCalibration.measured(1280, 720, 700, 700,
                640, 360, 1.5, pitch);
        GroundIntersection p = GroundPlaneGeometry.intersect(pitched, pitched.ray(640, 360), .03).orElseThrow();
        assertEquals(1.5 / Math.sin(pitch), p.opticalDepthMeters(), 1e-9);
        assertEquals(1.5 / Math.tan(pitch), p.groundForwardMeters(), 1e-9);
        assertTrue(GroundPlaneGeometry.intersect(CAL, CAL.ray(640, 360), .03).isEmpty());
        assertTrue(GroundPlaneGeometry.intersect(CAL, CAL.ray(640, 355), .03).isEmpty());
        CameraCalibration facingDown = CameraCalibration.measured(1280, 720, 700, 700,
                640, 360, 1.5, 1.5);
        assertTrue(GroundPlaneGeometry.intersect(facingDown, facingDown.ray(640, 700), .03).isEmpty(),
                "a ray intersecting the road behind the vehicle is invalid");
        CameraCalibration doubleResolution = CameraCalibration.measured(2560, 1440, 1400, 1400,
                1280, 720, 1.5, 0);
        assertEquals(hit.opticalDepthMeters(), GroundPlaneGeometry.intersect(doubleResolution,
                doubleResolution.ray(1420, 860), .03).orElseThrow().opticalDepthMeters(), 1e-9);
    }

    @Test void strictCalibrationFovIsApproximateNotMeasured() {
        CameraCalibration fov = CameraCalibration.fromVerticalFov(1280, 720, Math.PI / 3, 1.5, 0);
        assertEquals(CalibrationSource.FOV_DERIVED_APPROXIMATE, fov.source());
        assertEquals(720d / (2 * Math.tan(Math.PI / 6)), fov.fyPixels(), 1e-9);
        assertEquals(fov.fyPixels(), fov.fxPixels(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> CameraCalibration.fromVerticalFov(1280, 720, 0, 1.5, 0));
        assertThrows(IllegalArgumentException.class, () -> CameraCalibration.measured(1280, 720,
                Double.NaN, 700, 640, 360, 1.5, 0));
        assertThrows(IllegalArgumentException.class, () -> CameraCalibration.measured(1280, 720,
                700, 700, 640, 360, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CAL.ray(1280, 400));
        TrackedObject obj = track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0,
                observation(S, 15)).object();
        DistanceEstimate d = new GroundPlaneDistanceEstimator().estimate(obj, 1280, 720, fov, C);
        assertTrue(d.available());
        assertEquals(EvidenceQuality.LOW, d.quality());
        assertEquals(EvidenceQuality.LOW,
                new ObjectSizeDistanceEstimator(Map.of(ObjectClass.PERSON, SYNTHETIC))
                        .estimate(obj, 1280, 720, fov, C).quality());
    }

    @Test void groundAndSizePriorsHaveDistinctUncertaintyAndNoBlindFusion() {
        TrackedObject obj = track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0,
                observation(S, 15)).object();
        DistanceEstimate ground = new GroundPlaneDistanceEstimator().estimate(obj, 1280, 720, CAL, C);
        assertEquals(15d, ground.meters(), .0001);
        assertEquals(DistanceMethod.GROUND_PLANE, ground.method());
        assertFalse(ground.boundsAvailable());
        assertTrue(Double.isNaN(ground.lowerBoundMeters()));
        DistanceEstimate sized = new ObjectSizeDistanceEstimator(Map.of(ObjectClass.PERSON, SYNTHETIC))
                .estimate(obj, 1280, 720, CAL, C);
        assertTrue(sized.available());
        assertEquals(DistanceMethod.OBJECT_SIZE, sized.method());
        assertEquals(15d, sized.meters(), .0001);
        assertEquals(15d * 1.6 / 1.7, sized.lowerBoundMeters(), .0001);
        assertEquals(15d * 1.8 / 1.7, sized.upperBoundMeters(), .0001);
        assertTrue(sized.boundsAvailable());
        assertEquals(EvidenceQuality.MEDIUM, sized.quality());
        DistanceEstimate cross = new ConservativeDistanceEstimator(Map.of(ObjectClass.PERSON, SYNTHETIC))
                .estimate(obj, 1280, 720, CAL, C);
        assertEquals(DistanceMethod.GROUND_PLANE_CROSS_CHECKED, cross.method());
        assertEquals(ground.meters(), cross.meters(), 1e-10);
        assertFalse(cross.boundsAvailable());
        ObjectSizePrior contradictory = new ObjectSizePrior(ObjectClass.PERSON, SizeDimension.HEIGHT,
                .35, .4, .45, PriorSource.EXPERIMENTAL_UNVALIDATED);
        DistanceEstimate conflict = new ConservativeDistanceEstimator(Map.of(ObjectClass.PERSON, contradictory))
                .estimate(obj, 1280, 720, CAL, C);
        assertEquals(PhysicalReason.CONFLICTING_ESTIMATES, conflict.reason());
        assertTrue(Double.isNaN(conflict.meters()));
        // If horizon invalidates ground, the explicitly configured size prior can be a fallback.
        CameraCalibration upwards = CameraCalibration.measured(1280, 720, 700, 700,
                640, 500, 1.5, 0);
        DistanceEstimate fallback = new ConservativeDistanceEstimator(Map.of(ObjectClass.PERSON, SYNTHETIC))
                .estimate(obj, 1280, 720, upwards, C);
        assertEquals(DistanceMethod.OBJECT_SIZE, fallback.method());
        assertTrue(fallback.boundsAvailable());
    }

    @Test void missingCalibrationUnknownClassClippingAndResolutionReject() {
        TrackedObject obj = track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0,
                observation(S, 15)).object();
        ConservativeDistanceEstimator none = new ConservativeDistanceEstimator(Map.of());
        assertEquals(PhysicalReason.NO_CALIBRATION, none.estimate(obj, 1280, 720, null, C).reason());
        assertEquals(PhysicalReason.CALIBRATION_MISMATCH, none.estimate(obj, 640, 360, CAL, C).reason());
        assertEquals(PhysicalReason.NO_SIZE_PRIOR,
                new ObjectSizeDistanceEstimator(Map.of()).estimate(obj, 1280, 720, CAL, C).reason());
        assertEquals(PhysicalReason.UNKNOWN_CLASS, new ObjectSizeDistanceEstimator(ObjectSizePriors.experimentalUnvalidated())
                .estimate(track(7, ObjectClass.UNKNOWN, TrackState.CONFIRMED, 0, observation(S, 15)).object(),
                        1280, 720, CAL, C).reason());
        assertFalse(ObjectSizePriors.experimentalUnvalidated().containsKey(ObjectClass.UNKNOWN));
        TrackedObject noTimestamp = new TrackedObject(obj.trackId(), obj.objectClass(), obj.confidence(),
                obj.box(), obj.positionHistory(), obj.movement(), obj.estimatedDistance(),
                obj.estimatedTtc(), false, 1, 0);
        assertEquals(PhysicalReason.INVALID_TIMESTAMP,
                new GroundPlaneDistanceEstimator().estimate(noTimestamp, 1280, 720, CAL, C).reason());
        assertEquals(EvidenceQuality.LOW, new ObjectSizeDistanceEstimator(ObjectSizePriors.experimentalUnvalidated())
                .estimate(obj, 1280, 720, CAL, C).quality());
        TrackedObject clipped = track(8, ObjectClass.PERSON, TrackState.CONFIRMED, 0,
                new TrackObservation(S, new BoundingBox(600, 100, 680, 720), .9f)).object();
        assertEquals(PhysicalReason.INVALID_GEOMETRY,
                new GroundPlaneDistanceEstimator().estimate(clipped, 1280, 720, CAL, C).reason());
        assertEquals(PhysicalReason.INVALID_GEOMETRY,
                new ObjectSizeDistanceEstimator(Map.of(ObjectClass.PERSON, SYNTHETIC))
                        .estimate(clipped, 1280, 720, CAL, C).reason());
    }

    @Test void irregularSourceTimeLeastSquaresAndQualityGates() {
        long base = Long.MAX_VALUE - 4 * S;
        List<MetricDistanceSample> samples = List.of(sample(1, base, 24),
                sample(1, base + 200_000_000L, 23), sample(1, base + 1_500_000_000L, 16.5));
        RangeRateEstimate rate = new MetricRangeRateEstimator().fit(samples, C);
        assertTrue(rate.available());
        assertEquals(-5d, rate.rangeRateMps(), 1e-9);
        assertEquals(5d, rate.closingSpeedMps(), 1e-9);
        assertEquals(0d, rate.fitRmsMeters(), 1e-9);
        assertEquals(base + 1_500_000_000L, rate.timestampNanos());
        TtcEstimate ttc = new PhysicalTtcEstimator().metric(DistanceEstimate.of(16.5,
                DistanceMethod.GROUND_PLANE, EvidenceQuality.MEDIUM, rate.timestampNanos()), rate, C);
        assertEquals(3.3, ttc.seconds(), 1e-9);
        assertEquals(TtcMethod.METRIC_RANGE, ttc.method());
        assertEquals(PhysicalReason.INSUFFICIENT_HISTORY,
                new MetricRangeRateEstimator().fit(samples.subList(0, 2), C).reason());
        assertEquals(PhysicalReason.CROSS_TRACK_HISTORY, new MetricRangeRateEstimator().fit(List.of(
                sample(1, S, 24), sample(2, 2*S, 20), sample(1, 3*S, 16)), C).reason());
        assertEquals(PhysicalReason.INVALID_TIMESTAMP, new MetricRangeRateEstimator().fit(List.of(
                sample(1, S, 24), sample(1, S, 20), sample(1, 2*S, 16)), C).reason());
        assertEquals(PhysicalReason.INVALID_TIMESTAMP, new MetricRangeRateEstimator().fit(List.of(
                sample(1, 2*S, 24), sample(1, S, 20), sample(1, 3*S, 16)), C).reason());
        assertEquals(PhysicalReason.INVALID_TIMESTAMP, new MetricRangeRateEstimator().fit(List.of(
                sample(1, S, 24), sample(1, 4*S, 20), sample(1, 5*S, 16)), C).reason());
        assertEquals(PhysicalReason.LOW_QUALITY, new MetricRangeRateEstimator().fit(List.of(
                sample(1, S, 24), sample(1, 2*S, 200), sample(1, 3*S, 16)), C).reason());
    }

    @Test void recedingStationaryLowQualityAndOutOfRangeNeverEmitMetricTtc() {
        PhysicalTtcEstimator t = new PhysicalTtcEstimator();
        DistanceEstimate d = DistanceEstimate.of(12, DistanceMethod.GROUND_PLANE, EvidenceQuality.MEDIUM, 3*S);
        RangeRateEstimate receding = new MetricRangeRateEstimator().fit(List.of(
                sample(1, S, 10), sample(1, 2*S, 11), sample(1, 3*S, 12)), C);
        assertTrue(receding.available());
        assertEquals(0d, receding.closingSpeedMps()); // measured non-closing, NOT an unknown value
        assertEquals(PhysicalReason.NOT_CLOSING, t.metric(d, receding, C).reason());
        RangeRateEstimate stationary = new MetricRangeRateEstimator().fit(List.of(
                sample(1, S, 12), sample(1, 2*S, 12), sample(1, 3*S, 12)), C);
        assertEquals(PhysicalReason.NOT_CLOSING, t.metric(d, stationary, C).reason());
        assertEquals(PhysicalReason.LOW_QUALITY, t.metric(DistanceEstimate.bounded(12, 10, 14,
                DistanceMethod.OBJECT_SIZE, EvidenceQuality.LOW, 3*S), receding, C).reason());
        RangeRateEstimate slow = RangeRateEstimate.of(-.6, 0, 3, EvidenceQuality.MEDIUM, 3*S);
        assertEquals(PhysicalReason.OUT_OF_RANGE, t.metric(DistanceEstimate.of(30,
                DistanceMethod.GROUND_PLANE, EvidenceQuality.MEDIUM, 3*S), slow, C).reason());
        assertEquals(PhysicalReason.INVALID_TIMESTAMP, t.metric(d,
                RangeRateEstimate.of(-4, 0, 3, EvidenceQuality.MEDIUM, 4*S), C).reason());
    }

    @Test void metricHistoryUsesCurrentSourceTimesBoundedAndIsNeverMixedAcrossTracks() {
        PhysicalEstimationProcessor p = new PhysicalEstimationProcessor(CAL, Map.of(), C);
        ArrayList<TrackObservation> a = new ArrayList<>(), b = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            a.add(observation(i*S, 30 - i));
            b.add(observation(i*S, 18 + i));
            PhysicalEstimationSnapshot result = run(p, snap(i*S,
                    track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a.toArray(TrackObservation[]::new)),
                    track(8, ObjectClass.PERSON, TrackState.CONFIRMED, 0, b.toArray(TrackObservation[]::new))));
            assertTrue(result.available());
            if (i >= 3) {
                assertEquals(-1d, result.objects().get(0).rangeRate().rangeRateMps(), .002);
                assertEquals(1d, result.objects().get(1).rangeRate().rangeRateMps(), .002);
            }
        }
        assertEquals(C.maximumMetricSamples(), p.historyForTrack(7).size());
        assertEquals(C.maximumMetricSamples(), p.historyForTrack(8).size());
        assertEquals(2, p.retainedTrackCount());
        assertEquals(7, p.historyForTrack(7).get(0).trackId());
        assertThrows(UnsupportedOperationException.class, () -> p.historyForTrack(7).clear());
        assertFalse(new TrackedObject(7, ObjectClass.PERSON, .9f, a.get(0).box(), List.of(),
                MovementClass.UNKNOWN, Estimate.unavailable(), Estimate.unavailable(), false, 1, S)
                .estimatedDistance().available());
    }

    @Test void emptyLostFailureDuplicateAndRecreatedTrackResetHistory() {
        PhysicalEstimationProcessor p = new PhysicalEstimationProcessor(CAL, Map.of(), C);
        TrackObservation a = observation(S, 20), b = observation(2*S, 16), c = observation(3*S, 12);
        run(p, snap(S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a)));
        run(p, snap(2*S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a, b)));
        PhysicalEstimationSnapshot third = run(p, snap(3*S,
                track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a, b, c)));
        assertEquals(-4d, third.objects().get(0).rangeRate().rangeRateMps(), .002);
        assertEquals(3d, third.objects().get(0).metricTtc().seconds(), .002);
        PhysicalEstimationSnapshot dup = run(p, snap(3*S,
                track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a, b, c)));
        assertEquals(PhysicalEstimationSnapshot.Status.INVALID_TIMESTAMP, dup.status());
        assertTrue(dup.objects().isEmpty());
        assertEquals(0, p.retainedTrackCount());
        PhysicalEstimationSnapshot empty = run(p, snap(4*S));
        assertTrue(empty.available());
        assertTrue(empty.objects().isEmpty());
        assertEquals(0, p.retainedTrackCount());
        TrackObservation reused = observation(5*S, 12);
        PhysicalObjectEstimate fresh = run(p, snap(5*S,
                track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, reused))).objects().get(0);
        assertEquals(PhysicalReason.INSUFFICIENT_HISTORY, fresh.rangeRate().reason());
        assertEquals(1, p.historyForTrack(7).size());
        PhysicalObjectEstimate lost = run(p, snap(6*S,
                track(7, ObjectClass.PERSON, TrackState.LOST, 1, reused))).objects().get(0);
        assertEquals(PhysicalReason.TRACK_NOT_CURRENT, lost.distance().reason());
        assertEquals(0, p.retainedTrackCount());
        assertFalse(lost.imageScaleTtc().available());
        TrackingSnapshot failure = TrackingSnapshot.unavailable(7*S, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE);
        PhysicalEstimationSnapshot unavailable = p.analyze(failure, trajectories.estimate(failure));
        assertEquals(PhysicalEstimationSnapshot.Status.TRACKING_UNAVAILABLE, unavailable.status());
        assertEquals(TrackingSnapshot.Status.DETECTOR_UNAVAILABLE, unavailable.trackingStatus());
        assertTrue(unavailable.objects().isEmpty());
        assertEquals(0, p.retainedTrackCount());
        PhysicalObjectEstimate after = run(p, snap(8*S,
                track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, observation(8*S, 10))))
                .objects().get(0);
        assertEquals(PhysicalReason.INSUFFICIENT_HISTORY, after.rangeRate().reason());
        p.reset();
        assertEquals(0, p.retainedTrackCount());
        assertTrue(run(p, snap(S)).available()); // explicit source restart only
    }

    @Test void opticalTtcUsesSustainedGrowthOnlyAndNeverSuppliesMetricRate() {
        PhysicalEstimationProcessor p = PhysicalEstimationProcessor.unavailableByDefault();
        TrackObservation a = observation(S, 24), b = observation(2*S, 20), c = observation(3*S, 16);
        run(p, snap(S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a)));
        run(p, snap(2*S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a,b)));
        PhysicalObjectEstimate third = run(p, snap(3*S,
                track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a,b,c))).objects().get(0);
        assertEquals(PhysicalReason.NO_CALIBRATION, third.distance().reason());
        assertFalse(third.rangeRate().available());
        assertTrue(Double.isNaN(third.rangeRate().rangeRateMps()));
        assertFalse(third.metricTtc().available());
        assertTrue(third.imageScaleTtc().available());
        assertEquals(TtcMethod.IMAGE_SCALE, third.selectedTtc().method());
        assertEquals(EvidenceQuality.LOW, third.imageScaleTtc().quality());
        assertTrue(third.imageScaleTtc().seconds() > 0d);
        // One late box jump without sustained image growth is NOT a TTC.
        TrackObservation same1 = observation(4*S, 20), same2 = observation(5*S, 20), jump = observation(6*S, 16);
        PhysicalObjectEstimate late = run(p, snap(6*S,
                track(9, ObjectClass.PERSON, TrackState.CONFIRMED, 0, same1,same2,jump))).objects().get(0);
        assertEquals(PhysicalReason.INSUFFICIENT_HISTORY, late.imageScaleTtc().reason());
    }

    @Test void selectedTtcConflictIsUnavailableButMethodsStaySeparate() {
        PhysicalTtcEstimator estimator = new PhysicalTtcEstimator();
        TtcEstimate metric = TtcEstimate.of(3, TtcMethod.METRIC_RANGE, EvidenceQuality.MEDIUM, 3*S);
        TtcEstimate optical = TtcEstimate.of(12, TtcMethod.IMAGE_SCALE, EvidenceQuality.LOW, 3*S);
        TtcEstimate selected = estimator.selected(metric, optical, C);
        assertEquals(PhysicalReason.CONFLICTING_ESTIMATES, selected.reason());
        assertTrue(Double.isNaN(selected.seconds()));
        assertTrue(metric.available());
        assertTrue(optical.available());
        assertEquals(TtcMethod.METRIC_RANGE,
                estimator.selected(metric, TtcEstimate.of(4, TtcMethod.IMAGE_SCALE, EvidenceQuality.LOW, 3*S), C)
                        .method());
    }

    @Test void contractsAreImmutableRejectZeroUnknownsInfinityAndMalformedSnapshots() {
        assertThrows(IllegalArgumentException.class, () -> DistanceEstimate.unavailable(S, PhysicalReason.AVAILABLE));
        assertThrows(IllegalArgumentException.class, () -> DistanceEstimate.of(Double.POSITIVE_INFINITY,
                DistanceMethod.GROUND_PLANE, EvidenceQuality.MEDIUM, S));
        assertThrows(IllegalArgumentException.class, () -> TtcEstimate.of(0,
                TtcMethod.METRIC_RANGE, EvidenceQuality.MEDIUM, S));
        assertThrows(IllegalArgumentException.class, () -> TtcEstimate.of(2,
                TtcMethod.IMAGE_SCALE, EvidenceQuality.MEDIUM, S));
        assertThrows(IllegalArgumentException.class, () -> DistanceEstimate.of(10,
                DistanceMethod.OBJECT_SIZE, EvidenceQuality.MEDIUM, S));
        assertThrows(IllegalArgumentException.class, () -> PhysicalEstimationSnapshot.unavailable(S,
                PhysicalEstimationSnapshot.Status.TRACKING_UNAVAILABLE, TrackingSnapshot.Status.READY,
                TrajectorySnapshot.Status.READY));
        assertThrows(IllegalArgumentException.class, () -> RangeRateEstimate.unavailable(S,
                PhysicalReason.AVAILABLE, 0));
        assertThrows(IllegalArgumentException.class, () -> new ObjectSizePrior(ObjectClass.UNKNOWN,
                SizeDimension.HEIGHT, 1, 2, 3, PriorSource.EXPERIMENTAL_UNVALIDATED));
        assertThrows(IllegalArgumentException.class, () -> new ObjectSizePrior(ObjectClass.PERSON,
                SizeDimension.HEIGHT, 2, 1, 3, PriorSource.EXPERIMENTAL_UNVALIDATED));
        assertThrows(IllegalArgumentException.class, () -> new ObjectSizeDistanceEstimator(Map.of(
                ObjectClass.COW, SYNTHETIC)));
        PhysicalEstimationSnapshot empty = run(PhysicalEstimationProcessor.unavailableByDefault(), snap(S));
        assertTrue(empty.available());
        assertThrows(UnsupportedOperationException.class, () -> empty.objects().clear());
        assertThrows(IllegalArgumentException.class, () -> new PhysicalEstimationSnapshot(S, 1280, 720,
                PhysicalEstimationSnapshot.Status.TRACKING_UNAVAILABLE, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE,
                TrajectorySnapshot.Status.TRACKING_UNAVAILABLE, List.of(new PhysicalObjectEstimate(7,
                        ObjectClass.PERSON, TrackState.TENTATIVE, S,
                        DistanceEstimate.unavailable(S, PhysicalReason.TRACK_NOT_CURRENT),
                        RangeRateEstimate.unavailable(S, PhysicalReason.TRACK_NOT_CURRENT, 0),
                        TtcEstimate.unavailable(S, PhysicalReason.TRACK_NOT_CURRENT),
                        TtcEstimate.unavailable(S, PhysicalReason.TRACK_NOT_CURRENT),
                        TtcEstimate.unavailable(S, PhysicalReason.TRACK_NOT_CURRENT)))));
    }

    @Test void mismatchedSourceTimesTrajectoryFailureAndCapacityAreNotEmptySuccess() {
        PhysicalEstimationProcessor p = new PhysicalEstimationProcessor(CAL, Map.of(), C);
        TrackObservation a = observation(S, 20);
        TrackingSnapshot tracking = snap(S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a));
        assertEquals(PhysicalEstimationSnapshot.Status.INVALID_TIMESTAMP, p.analyze(tracking,
                trajectories.estimate(snap(2*S))).status());
        TrajectorySnapshot badBox = new TrajectorySnapshot(S, 1280, 720, TrajectorySnapshot.Status.READY,
                TrackingSnapshot.Status.READY, List.of(kz.zholsafe.trajectory.ObjectTrajectory.unavailable(
                        7, ObjectClass.PERSON, S, new BoundingBox(500, 300, 600, 400), 1,
                        kz.zholsafe.trajectory.TrajectoryStatus.INSUFFICIENT_HISTORY)));
        assertEquals(PhysicalEstimationSnapshot.Status.ESTIMATOR_ERROR, p.analyze(tracking, badBox).status());
        TrackedObject forged = new TrackedObject(7, ObjectClass.PERSON, .9f,
                new BoundingBox(500, 300, 600, 400), List.of(), MovementClass.UNKNOWN,
                Estimate.unavailable(), Estimate.unavailable(), false, 1, S);
        TrackingSnapshot inconsistentBox = snap(S, new TrackView(forged, TrackState.CONFIRMED,
                1, 0, List.of(a)));
        assertEquals(PhysicalEstimationSnapshot.Status.INVALID_TIMESTAMP,
                run(p, inconsistentBox).status());
        assertEquals(0, p.retainedTrackCount());
        TrajectorySnapshot upstreamError = TrajectorySnapshot.unavailable(S,
                TrajectorySnapshot.Status.ESTIMATOR_ERROR, TrackingSnapshot.Status.READY);
        assertEquals(PhysicalEstimationSnapshot.Status.TRAJECTORY_UNAVAILABLE,
                p.analyze(tracking, upstreamError).status());
        assertEquals(PhysicalEstimationSnapshot.Status.INVALID_TIMESTAMP, p.analyze(
                snap(S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a,
                        observation(S, 19))), trajectories.estimate(snap(S, track(7, ObjectClass.PERSON,
                        TrackState.CONFIRMED, 0, a, observation(S, 19))))).status());
        // A successful empty frame is READY, not either kind of upstream error.
        assertTrue(run(p, snap(3*S)).available());
    }

    @Test void staleGapsAndSourceTimestampMismatchNeverBridgePhysicalHistory() {
        PhysicalEstimationProcessor p = new PhysicalEstimationProcessor(CAL, Map.of(), C);
        TrackObservation a = observation(S, 24), b = observation(2*S, 20), c = observation(6*S, 16);
        run(p, snap(S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a)));
        run(p, snap(2*S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a,b)));
        PhysicalObjectEstimate afterGap = run(p, snap(6*S,
                track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a,b,c))).objects().get(0);
        assertEquals(PhysicalReason.INSUFFICIENT_HISTORY, afterGap.rangeRate().reason());
        assertEquals(1, p.historyForTrack(7).size());
        TrackObservation inconsistent = observation(7*S, 12);
        TrackingSnapshot wrongSource = snap(8*S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, inconsistent));
        assertEquals(PhysicalEstimationSnapshot.Status.INVALID_TIMESTAMP,
                run(p, wrongSource).status());
        assertTrue(p.historyForTrack(7).isEmpty());
        assertEquals(PhysicalEstimationSnapshot.Status.INVALID_TIMESTAMP, run(p, snap(5*S)).status());
    }

    @Test void capacityLimitRejectsRatherThanDropsActiveTrackHistory() {
        PhysicalEstimationConfig tiny = new PhysicalEstimationConfig(C.horizonRayMargin(),
                C.minimumBoxHeightPixels(), C.fusionRelativeTolerance(),
                C.maxPriorRelativeWidthForMedium(), C.minimumMetricSamples(), C.maximumMetricSamples(),
                C.minimumMetricTimeSpanSeconds(), C.maximumRangeFitRmsMeters(),
                C.maximumMetricSampleGapSeconds(), C.minimumClosingSpeedMps(),
                C.minimumScaleGrowthRatePerSecond(), C.maximumScaleFitRms(),
                C.maximumReportedTtcSeconds(), C.ttcAgreementRelativeTolerance(), 1,
                C.minimumDistanceQualityForRate(), C.minimumRateQualityForTtc());
        PhysicalEstimationProcessor p = new PhysicalEstimationProcessor(CAL, Map.of(), tiny);
        TrackObservation obs = observation(S, 15);
        PhysicalEstimationSnapshot result = run(p, snap(S,
                track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, obs),
                track(8, ObjectClass.PERSON, TrackState.CONFIRMED, 0, obs)));
        assertEquals(PhysicalEstimationSnapshot.Status.ESTIMATOR_ERROR, result.status());
        assertEquals(0, p.retainedTrackCount());
        assertTrue(result.objects().isEmpty());
    }

    @Test void measuredSizeFallbackCanRateWhenGroundRayCannotIntersect() {
        CameraCalibration horizon = CameraCalibration.measured(1280, 720, 700, 700,
                640, 500, 1.5, 0);
        PhysicalEstimationProcessor p = new PhysicalEstimationProcessor(horizon,
                Map.of(ObjectClass.PERSON, SYNTHETIC), C);
        TrackObservation a = observation(S, 24), b = observation(2*S, 20), c = observation(3*S, 16);
        run(p, snap(S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a)));
        run(p, snap(2*S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a,b)));
        PhysicalObjectEstimate third = run(p, snap(3*S,
                track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a,b,c))).objects().get(0);
        assertEquals(DistanceMethod.OBJECT_SIZE, third.distance().method());
        assertEquals(EvidenceQuality.MEDIUM, third.distance().quality());
        assertEquals(-4d, third.rangeRate().rangeRateMps(), .002);
        assertTrue(third.metricTtc().available());
        assertEquals(4d, third.metricTtc().seconds(), .002);
    }

    @Test void recreatedSameIdWithoutHistoryContinuityAndClassSwitchNeverBridge() {
        PhysicalEstimationProcessor p = new PhysicalEstimationProcessor(CAL, Map.of(), C);
        TrackObservation a = observation(S, 24), b = observation(2*S, 20), c = observation(3*S, 16);
        run(p, snap(S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a)));
        run(p, snap(2*S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a,b)));
        assertTrue(run(p, snap(3*S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, a,b,c)))
                .objects().get(0).rangeRate().available());
        // Same numeric ID with only new history -> different lifecycle, even without empty frame.
        TrackObservation recreated = observation(4*S, 12);
        PhysicalObjectEstimate again = run(p, snap(4*S,
                track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0, recreated))).objects().get(0);
        assertEquals(PhysicalReason.INSUFFICIENT_HISTORY, again.rangeRate().reason());
        assertEquals(1, p.historyForTrack(7).size());
        PhysicalObjectEstimate changedClass = run(p, snap(5*S, track(7, ObjectClass.COW,
                TrackState.CONFIRMED, 0, recreated, observation(5*S, 11)))).objects().get(0);
        assertEquals(PhysicalReason.INSUFFICIENT_HISTORY, changedClass.rangeRate().reason());
        assertEquals(1, p.historyForTrack(7).size());
    }
}
