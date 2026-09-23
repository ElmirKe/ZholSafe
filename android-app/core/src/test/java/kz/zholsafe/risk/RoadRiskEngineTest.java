package kz.zholsafe.risk;

import kz.zholsafe.config.RoadRiskConfig;
import kz.zholsafe.config.TrajectoryConfig;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Estimate;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.physical.*;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;
import kz.zholsafe.tracking.*;
import kz.zholsafe.trajectory.LinearImageTrajectoryEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class RoadRiskEngineTest {
    private static final long S = 1_000_000_000L;
    private static final int W = 1280, H = 720;
    private static final RoadRiskConfig CONFIG = RoadRiskConfig.defaults();
    private static final RoadRiskEngine ENGINE = new RoadRiskEngine(CONFIG);

    private static BoundingBox box(double cx, double bottom, double width, double height) {
        return new BoundingBox((float) (cx - width/2), (float) (bottom-height),
                (float) (cx + width/2), (float) bottom);
    }
    private static TrackObservation obs(long ts, BoundingBox b) { return new TrackObservation(ts, b, .9f); }
    private static TrackView track(int id, ObjectClass cls, TrackState state, int missed,
                                   TrackObservation... observations) {
        TrackObservation last = observations[observations.length-1];
        TrackedObject obj = new TrackedObject(id, cls, last.confidence(), last.box(),
                List.of(observations).stream().map(TrackObservation::center).toList(),
                MovementClass.UNKNOWN, Estimate.unavailable(), Estimate.unavailable(), false,
                observations.length, last.timestampNanos());
        return new TrackView(obj, state, observations.length, missed, List.of(observations));
    }
    private static TrackingSnapshot snap(long ts, TrackView... tracks) {
        return new TrackingSnapshot(ts, W, H, TrackingSnapshot.Status.READY, List.of(tracks));
    }
    private static TrajectorySnapshot path(TrackingSnapshot t) {
        return new LinearImageTrajectoryEstimator(TrajectoryConfig.defaults()).estimate(t);
    }
    private static PhysicalEstimationSnapshot noCalibration(TrackingSnapshot t, TrajectorySnapshot path) {
        return PhysicalEstimationProcessor.unavailableByDefault().analyze(t, path);
    }
    private static RoadRiskSnapshot risk(TrackingSnapshot t) {
        TrajectorySnapshot trajectory = path(t);
        return ENGINE.evaluate(t, trajectory, noCalibration(t, trajectory));
    }
    private static ObjectRiskAssessment single(BoundingBox box, ObjectClass cls) {
        return risk(snap(3*S, track(7, cls, TrackState.CONFIRMED, 0, obs(3*S, box)))).objects().get(0);
    }
    private static PhysicalObjectEstimate metric(int id, ObjectClass cls, long ts, double d,
                                                  double rate, double ttc) {
        DistanceEstimate depth = DistanceEstimate.of(d, DistanceMethod.GROUND_PLANE,
                EvidenceQuality.MEDIUM, ts);
        RangeRateEstimate speed = RangeRateEstimate.of(rate, 0, 3, EvidenceQuality.MEDIUM, ts);
        TtcEstimate metric = ttc > 0 ? TtcEstimate.of(ttc, TtcMethod.METRIC_RANGE,
                EvidenceQuality.MEDIUM, ts) : TtcEstimate.unavailable(ts, PhysicalReason.NOT_CLOSING);
        return new PhysicalObjectEstimate(id, cls, TrackState.CONFIRMED, ts, depth, speed,
                metric, TtcEstimate.unavailable(ts, PhysicalReason.INSUFFICIENT_HISTORY), metric);
    }
    private static PhysicalEstimationSnapshot with(TrackingSnapshot t, PhysicalObjectEstimate... values) {
        return new PhysicalEstimationSnapshot(t.frameTimestampNanos(), W, H,
                PhysicalEstimationSnapshot.Status.READY, TrackingSnapshot.Status.READY,
                TrajectorySnapshot.Status.READY, List.of(values));
    }
    private static RoadRiskSnapshot riskMetric(int id, ObjectClass cls, BoundingBox b,
                                               double d, double rate, double ttc) {
        TrackingSnapshot t = snap(3*S, track(id, cls, TrackState.CONFIRMED, 0, obs(3*S, b)));
        return ENGINE.evaluate(t, path(t), with(t, metric(id, cls, 3*S, d, rate, ttc)));
    }
    private static boolean has(ObjectRiskAssessment a, RiskEvidenceType type) {
        return a.evidence().stream().anyMatch(e -> e.type() == type);
    }

    @Test void corridorCentralOutsideEdgeResolutionAndWidening() {
        NormalizedDrivingCorridor c = CONFIG.corridor();
        assertEquals(CorridorRelation.CENTRAL, c.classify(box(640, 550, 60, 70), W, H,
                CONFIG.nearMarginFraction(), CONFIG.centralHalfWidthFraction()));
        assertEquals(CorridorRelation.OUTSIDE, c.classify(box(100, 550, 40, 70), W, H,
                CONFIG.nearMarginFraction(), CONFIG.centralHalfWidthFraction()));
        // y=.5 gives halfWidth=.2; the bbox RIGHT edge at .3 touches LEFT corridor edge.
        BoundingBox touch = new BoundingBox(150, 90, 384, 360);
        assertEquals(CorridorRelation.INTERSECTING, c.classify(touch, W, H, 0, .40));
        assertEquals(CorridorRelation.INTERSECTING,
                c.classify(new BoundingBox(75, 45, 192, 180), 640, 360, 0, .40));
        assertTrue(c.containsContact(.35, .9));
        assertFalse(c.containsContact(.35, .31));
        assertTrue(has(single(box(640, 550, 60, 70), ObjectClass.DOG),
                RiskEvidenceType.OBJECT_CENTRAL));
    }

    @Test void outsideMovingTowardAndPredictedEntryButAwayAndJitterDoNotEscalate() {
        TrackObservation a = obs(S, box(70, 450, 20, 50));
        TrackObservation b = obs(2*S, box(170, 450, 20, 50));
        TrackObservation c = obs(3*S, box(270, 450, 20, 50));
        ObjectRiskAssessment toward = risk(snap(3*S,
                track(7, ObjectClass.DOG, TrackState.CONFIRMED, 0, a,b,c))).objects().get(0);
        assertTrue(has(toward, RiskEvidenceType.MOVING_TOWARD_CORRIDOR));
        assertTrue(has(toward, RiskEvidenceType.PREDICTED_CORRIDOR_ENTRY));
        assertTrue(toward.level().isAtLeast(RiskLevel.CAUTION));
        ObjectRiskAssessment away = risk(snap(3*S, track(7, ObjectClass.DOG, TrackState.CONFIRMED, 0,
                obs(S, box(270,450,20,50)),obs(2*S,box(170,450,20,50)),obs(3*S,box(70,450,20,50)))))
                .objects().get(0);
        assertFalse(has(away, RiskEvidenceType.MOVING_TOWARD_CORRIDOR));
        assertFalse(has(away, RiskEvidenceType.PREDICTED_CORRIDOR_ENTRY));
        ObjectRiskAssessment jitter = risk(snap(3*S, track(7, ObjectClass.DOG, TrackState.CONFIRMED, 0,
                obs(S,box(268,450,20,50)),obs(2*S,box(269,450,20,50)),obs(3*S,box(268,450,20,50)))))
                .objects().get(0);
        assertFalse(has(jitter, RiskEvidenceType.MOVING_TOWARD_CORRIDOR));
        assertFalse(has(jitter, RiskEvidenceType.PREDICTED_CORRIDOR_ENTRY));
    }

    @Test void insufficientTrajectoryIsNotAssumedStationary() {
        ObjectRiskAssessment a = single(box(640, 550, 50, 80), ObjectClass.PERSON);
        assertTrue(has(a, RiskEvidenceType.TRAJECTORY_UNAVAILABLE));
        assertFalse(has(a, RiskEvidenceType.APPARENT_RECEDE));
        assertFalse(has(a, RiskEvidenceType.APPARENT_APPROACH));
    }

    @Test void shortMetricTtcInsideCriticalOutsideLowerRecedingAndFarFast() {
        BoundingBox central = box(640, 550, 70, 80), outside = box(100, 550, 70, 80);
        RoadRiskSnapshot center = riskMetric(7, ObjectClass.PERSON, central, 12, -8, 1.5);
        assertEquals(RiskLevel.CRITICAL, center.highestLevel().orElseThrow());
        assertEquals(OptionalInt.of(7), center.highestRiskTrackId());
        assertTrue(has(center.objects().get(0), RiskEvidenceType.METRIC_TTC_SHORT));
        assertTrue(center.objects().get(0).reasons().contains(RiskReason.SHORT_METRIC_TTC));
        RoadRiskSnapshot edge = riskMetric(7, ObjectClass.PERSON, outside, 12, -8, 1.5);
        assertTrue(edge.highestLevel().orElseThrow().ordinal() < center.highestLevel().orElseThrow().ordinal());
        assertEquals(RiskLevel.NORMAL, riskMetric(7, ObjectClass.PERSON, outside,
                10, 2, -1).highestLevel().orElseThrow(), "close but receding outside is not critical");
        assertEquals(RiskLevel.CRITICAL, riskMetric(7, ObjectClass.PERSON,
                central, 100, -40, 2.5).highestLevel().orElseThrow(), "TTC may dominate raw distance");
    }

    @Test void noCalibrationOutsideStableIsNormalInsideOpticalExpansionEscalates() {
        ObjectRiskAssessment outside = single(box(100, 550, 40, 60), ObjectClass.PERSON);
        assertEquals(RiskLevel.NORMAL, outside.level());
        assertTrue(has(outside, RiskEvidenceType.PHYSICAL_ESTIMATE_UNAVAILABLE));
        assertEquals(PhysicalReason.NO_CALIBRATION, outside.evidence().stream()
                .filter(e -> e.type() == RiskEvidenceType.PHYSICAL_ESTIMATE_UNAVAILABLE)
                .findFirst().orElseThrow().physicalReason().orElseThrow());
        assertFalse(has(outside, RiskEvidenceType.METRIC_TTC_SHORT));
        TrackView expanding = track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0,
                obs(S, box(640, 550, 25, 25)), obs(2*S, box(640, 550, 40, 40)),
                obs(3*S, box(640, 550, 60, 60)));
        TrackingSnapshot t = snap(3*S, expanding);
        TrajectorySnapshot p = path(t);
        PhysicalEstimationSnapshot physical = noCalibration(t, p);
        assertTrue(physical.objects().get(0).imageScaleTtc().available());
        assertFalse(physical.objects().get(0).metricTtc().available());
        RoadRiskSnapshot risk = ENGINE.evaluate(t,p,physical);
        assertTrue(risk.available());
        assertTrue(risk.highestLevel().orElseThrow().isAtLeast(RiskLevel.WARNING));
        assertTrue(has(risk.objects().get(0), RiskEvidenceType.IMAGE_SCALE_TTC_SHORT));
        assertEquals(EvidenceQuality.LOW, risk.objects().get(0).evidenceQuality());
        // Same short optical TTC outside with no predicted entry cannot be CRITICAL.
        TrackingSnapshot outsideT = snap(3*S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0,
                obs(S, box(100, 550, 25,25)), obs(2*S,box(100,550,40,40)),
                obs(3*S,box(100,550,60,60))));
        RoadRiskSnapshot outsideOpt = risk(outsideT);
        assertFalse(outsideOpt.highestLevel().orElseThrow() == RiskLevel.CRITICAL);
        assertFalse(has(outsideOpt.objects().get(0), RiskEvidenceType.IMAGE_SCALE_TTC_SHORT));
    }

    @Test void conflictNeverCherryPicksShorterTtc() {
        TrackingSnapshot t = snap(3*S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0,
                obs(S, box(640,550,25,25)), obs(2*S,box(640,550,40,40)),
                obs(3*S,box(640,550,60,60))));
        TtcEstimate metric = TtcEstimate.of(1, TtcMethod.METRIC_RANGE, EvidenceQuality.MEDIUM, 3*S);
        TtcEstimate optical = TtcEstimate.of(7, TtcMethod.IMAGE_SCALE, EvidenceQuality.LOW, 3*S);
        PhysicalObjectEstimate o = new PhysicalObjectEstimate(7, ObjectClass.PERSON, TrackState.CONFIRMED, 3*S,
                DistanceEstimate.of(8, DistanceMethod.GROUND_PLANE, EvidenceQuality.MEDIUM, 3*S),
                RangeRateEstimate.of(-8, 0, 3, EvidenceQuality.MEDIUM, 3*S),
                metric, optical, TtcEstimate.unavailable(3*S, PhysicalReason.CONFLICTING_ESTIMATES));
        ObjectRiskAssessment result = ENGINE.evaluate(t, path(t), with(t,o)).objects().get(0);
        assertTrue(has(result, RiskEvidenceType.TTC_CONFLICT));
        assertEquals(PhysicalReason.CONFLICTING_ESTIMATES, result.evidence().stream()
                .filter(e -> e.type() == RiskEvidenceType.TTC_CONFLICT)
                .findFirst().orElseThrow().physicalReason().orElseThrow());
        assertFalse(has(result, RiskEvidenceType.METRIC_TTC_SHORT));
        assertFalse(has(result, RiskEvidenceType.IMAGE_SCALE_TTC_SHORT));
        assertFalse(result.level() == RiskLevel.CRITICAL);
    }

    @Test void largeClassAloneOutsideNeverCriticalPersonCanBeCriticalDogCrossingUnknownSafe() {
        assertEquals(RiskLevel.NORMAL, single(box(100,550,60,80), ObjectClass.HORSE).level());
        assertEquals(RiskLevel.CRITICAL, riskMetric(7, ObjectClass.PERSON,
                box(640,550,70,80), 12, -8, 1.5).highestLevel().orElseThrow());
        ObjectRiskAssessment dog = risk(snap(3*S, track(7, ObjectClass.DOG, TrackState.CONFIRMED, 0,
                obs(S,box(70,450,20,50)),obs(2*S,box(170,450,20,50)),
                obs(3*S,box(270,450,20,50))))).objects().get(0);
        assertTrue(dog.reasons().contains(RiskReason.MOVING_TOWARD_CORRIDOR));
        ObjectRiskAssessment unknown = single(box(100,550,60,80), ObjectClass.UNKNOWN);
        assertEquals(RiskLevel.NORMAL, unknown.level());
        assertFalse(has(unknown, RiskEvidenceType.LARGE_HAZARD_CLASS));
        assertFalse(has(unknown, RiskEvidenceType.METRIC_DISTANCE_AVAILABLE));
    }

    @Test void multiObjectMaxNotSumAndLowerIdWinsTie() {
        TrackView a = track(9, ObjectClass.PERSON, TrackState.CONFIRMED, 0, obs(3*S,box(100,550,60,80)));
        TrackView b = track(7, ObjectClass.DOG, TrackState.CONFIRMED, 0, obs(3*S,box(640,550,60,80)));
        TrackingSnapshot t = snap(3*S,a,b);
        RoadRiskSnapshot result = risk(t);
        assertEquals(RiskLevel.NORMAL, result.objects().get(0).level());
        assertEquals(RiskLevel.CAUTION, result.highestLevel().orElseThrow());
        assertEquals(OptionalInt.of(7), result.highestRiskTrackId());
        TrackView c = track(4, ObjectClass.PERSON, TrackState.CONFIRMED, 0, obs(3*S,box(640,550,60,80)));
        RoadRiskSnapshot tied = risk(snap(3*S,b,c));
        assertEquals(RiskLevel.CAUTION, tied.highestLevel().orElseThrow());
        assertEquals(OptionalInt.of(4), tied.highestRiskTrackId());
        RoadRiskSnapshot differentScores = risk(snap(3*S,
                track(4,ObjectClass.HORSE,TrackState.CONFIRMED,0,obs(3*S,box(205,550,30,80))),
                track(9,ObjectClass.DOG,TrackState.CONFIRMED,0,obs(3*S,box(640,550,60,80)))));
        assertEquals(RiskLevel.CAUTION, differentScores.highestLevel().orElseThrow());
        assertEquals(OptionalInt.of(9), differentScores.highestRiskTrackId(),
                "within one level choose the higher engineering score before ID tie-break");
        TrackingSnapshot criticalT = snap(3*S,a,b);
        PhysicalObjectEstimate normal = PhysicalEstimationProcessor.unavailableByDefault()
                .analyze(criticalT,path(criticalT)).objects().get(0);
        RoadRiskSnapshot critical = ENGINE.evaluate(criticalT,path(criticalT),
                with(criticalT, normal, metric(7,ObjectClass.DOG,3*S,12,-8,1.5)));
        assertEquals(RiskLevel.CRITICAL, critical.highestLevel().orElseThrow());
        assertEquals(OptionalInt.of(7), critical.highestRiskTrackId());
    }

    @Test void failuresMismatchLostAndEmptyAreNotConflated() {
        TrackingSnapshot unavailable = TrackingSnapshot.unavailable(3*S, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE);
        TrajectorySnapshot trajFail = TrajectorySnapshot.unavailable(3*S,
                TrajectorySnapshot.Status.TRACKING_UNAVAILABLE, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE);
        PhysicalEstimationSnapshot physFail = PhysicalEstimationSnapshot.unavailable(3*S,
                PhysicalEstimationSnapshot.Status.TRACKING_UNAVAILABLE,
                TrackingSnapshot.Status.DETECTOR_UNAVAILABLE, TrajectorySnapshot.Status.TRACKING_UNAVAILABLE);
        RoadRiskSnapshot failed = ENGINE.evaluate(unavailable, trajFail, physFail);
        assertEquals(RoadRiskSnapshot.Status.TRACKING_UNAVAILABLE, failed.status());
        assertFalse(failed.available());
        assertTrue(failed.highestLevel().isEmpty(), "unknown is NOT NORMAL");
        TrackingSnapshot t = snap(3*S);
        assertEquals(RoadRiskSnapshot.Status.TRAJECTORY_UNAVAILABLE, ENGINE.evaluate(t,
                TrajectorySnapshot.unavailable(3*S, TrajectorySnapshot.Status.ESTIMATOR_ERROR,
                        TrackingSnapshot.Status.READY), physFail).status());
        TrajectorySnapshot path = path(t);
        assertEquals(RoadRiskSnapshot.Status.PHYSICAL_UNAVAILABLE, ENGINE.evaluate(t,path,
                PhysicalEstimationSnapshot.unavailable(3*S, PhysicalEstimationSnapshot.Status.ESTIMATOR_ERROR,
                        TrackingSnapshot.Status.READY, TrajectorySnapshot.Status.READY)).status());
        assertEquals(RoadRiskSnapshot.Status.INVALID_TIMESTAMP, ENGINE.evaluate(t,path,
                new PhysicalEstimationSnapshot(4*S,W,H,PhysicalEstimationSnapshot.Status.READY,
                        TrackingSnapshot.Status.READY, TrajectorySnapshot.Status.READY,List.of())).status());
        assertEquals(RoadRiskSnapshot.Status.INVALID_TIMESTAMP, ENGINE.evaluate(t,path(snap(4*S)),
                noCalibration(t,path)).status());
        assertEquals(RoadRiskSnapshot.Status.INVALID_TIMESTAMP, ENGINE.evaluate(t,path,
                new PhysicalEstimationSnapshot(3*S,W/2,H,PhysicalEstimationSnapshot.Status.READY,
                        TrackingSnapshot.Status.READY, TrajectorySnapshot.Status.READY,List.of())).status());
        RoadRiskSnapshot empty = risk(t);
        assertTrue(empty.available());
        assertEquals(RiskLevel.NORMAL, empty.highestLevel().orElseThrow());
        assertTrue(empty.objects().isEmpty());
        TrackObservation last = obs(2*S, box(640,550,60,80));
        RoadRiskSnapshot lost = risk(snap(3*S,track(7,ObjectClass.PERSON,TrackState.LOST,1,last)));
        assertTrue(lost.available());
        assertTrue(lost.objects().isEmpty());
        RoadRiskSnapshot tentative = risk(snap(3*S,track(7,ObjectClass.PERSON,TrackState.TENTATIVE,0,
                obs(3*S,box(640,550,60,80)))));
        assertTrue(tentative.objects().isEmpty());
    }

    @Test void contractsConfigImmutabilityUnitsScoreAndDeterminism() {
        TrackingSnapshot t = snap(3*S,track(7,ObjectClass.PERSON,TrackState.CONFIRMED,0,
                obs(3*S,box(640,550,60,80))));
        RoadRiskSnapshot first = risk(t), second = risk(t);
        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class, () -> first.objects().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.objects().get(0).evidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.objects().get(0).reasons().clear());
        assertThrows(IllegalArgumentException.class, () -> new ObjectRiskAssessment(7,ObjectClass.PERSON,
                3*S,RiskLevel.WARNING,.5,EvidenceQuality.LOW,new RiskComponents(.5,0,0,0,0,0),
                List.of(),List.of(RiskEvidence.flag(RiskEvidenceType.OBJECT_CENTRAL,
                        EvidenceSource.TRACKING,EvidenceQuality.LOW))));
        assertThrows(IllegalArgumentException.class, () -> new RiskEvidence(
                RiskEvidenceType.METRIC_DISTANCE_AVAILABLE,EvidenceSource.METRIC_RANGE,
                EvidenceQuality.MEDIUM,true,Double.NaN,EvidenceUnit.METERS_OPTICAL_DEPTH));
        assertThrows(IllegalArgumentException.class, () -> RiskEvidence.value(
                RiskEvidenceType.METRIC_DISTANCE_AVAILABLE,EvidenceSource.METRIC_RANGE,
                EvidenceQuality.MEDIUM,12,EvidenceUnit.SECONDS));
        assertThrows(IllegalArgumentException.class, () -> RiskEvidence.flag(
                RiskEvidenceType.METRIC_TTC_SHORT, EvidenceSource.METRIC_TTC, EvidenceQuality.MEDIUM));
        assertThrows(IllegalArgumentException.class, () -> RiskEvidence.value(
                RiskEvidenceType.METRIC_DISTANCE_AVAILABLE, EvidenceSource.METRIC_RANGE,
                EvidenceQuality.MEDIUM, -2, EvidenceUnit.METERS_OPTICAL_DEPTH));
        assertThrows(IllegalArgumentException.class, () -> new ObjectRiskAssessment(7, ObjectClass.PERSON,
                3*S, RiskLevel.CAUTION, .25, EvidenceQuality.LOW,
                new RiskComponents(.2,0,0,0,0,0), List.of(RiskReason.OBJECT_IN_DRIVING_CORRIDOR),
                List.of(RiskEvidence.flag(RiskEvidenceType.OBJECT_CENTRAL,
                        EvidenceSource.TRACKING, EvidenceQuality.LOW))));
        assertThrows(IllegalArgumentException.class, () -> RiskEvidence.value(
                RiskEvidenceType.APPARENT_APPROACH, EvidenceSource.IMAGE_TRAJECTORY,
                EvidenceQuality.LOW, .4, EvidenceUnit.NORMALIZED_PER_SECOND));
        assertThrows(IllegalArgumentException.class, () -> new RoadRiskConfig.TtcBands(5,3,7));
        assertThrows(IllegalArgumentException.class, () -> new RoadRiskConfig.ScoreBands(.3,.2,.8));
        assertThrows(IllegalArgumentException.class, () -> new NormalizedDrivingCorridor(.5,.3,.4,.2));
        assertEquals(0, first.objects().get(0).components().relativeClosing(),1e-12);
        assertTrue(first.objects().get(0).engineeringScore() <= 1d);
        for (var field : RoadRiskEngine.class.getDeclaredFields()) {
            assertFalse(field.getType() == kz.zholsafe.ai.Frame.class);
            assertFalse(field.getType() == java.nio.ByteBuffer.class);
            assertFalse(java.util.Map.class.isAssignableFrom(field.getType()),
                    "engine has no retained per-track/unbounded state");
        }
    }

    @Test void twoWarningTracksRemainWarningWithDeterministicTie() {
        TrackView first = track(9, ObjectClass.PERSON, TrackState.CONFIRMED, 0,
                obs(3*S, box(640, 550, 70, 80)));
        TrackView second = track(4, ObjectClass.DOG, TrackState.CONFIRMED, 0,
                obs(3*S, box(640, 550, 70, 80)));
        TrackingSnapshot t = snap(3*S, first, second);
        PhysicalObjectEstimate warning9 = metric(9, ObjectClass.PERSON, 3*S, 16, -4, 4);
        PhysicalObjectEstimate warning4 = metric(4, ObjectClass.DOG, 3*S, 16, -4, 4);
        RoadRiskSnapshot result = ENGINE.evaluate(t, path(t), with(t, warning9, warning4));
        assertEquals(RiskLevel.WARNING, result.objects().get(0).level());
        assertEquals(RiskLevel.WARNING, result.objects().get(1).level());
        assertEquals(RiskLevel.WARNING, result.highestLevel().orElseThrow());
        assertEquals(OptionalInt.of(4), result.highestRiskTrackId());
    }

    @Test void approximateFovDepthDoesNotBecomeMetricRiskOrTtc() {
        TrackingSnapshot t = snap(3*S, track(7, ObjectClass.PERSON, TrackState.CONFIRMED, 0,
                obs(3*S, box(640,550,70,80))));
        TtcEstimate shortMetric = TtcEstimate.of(1.5, TtcMethod.METRIC_RANGE,
                EvidenceQuality.MEDIUM, 3*S);
        PhysicalObjectEstimate low = new PhysicalObjectEstimate(7, ObjectClass.PERSON,
                TrackState.CONFIRMED, 3*S,
                DistanceEstimate.of(12, DistanceMethod.GROUND_PLANE, EvidenceQuality.LOW, 3*S),
                RangeRateEstimate.of(-8, 0, 3, EvidenceQuality.MEDIUM, 3*S), shortMetric,
                TtcEstimate.unavailable(3*S, PhysicalReason.INSUFFICIENT_HISTORY), shortMetric);
        ObjectRiskAssessment result = ENGINE.evaluate(t,path(t),with(t,low)).objects().get(0);
        assertTrue(has(result, RiskEvidenceType.PHYSICAL_LOW_QUALITY));
        assertFalse(has(result, RiskEvidenceType.METRIC_TTC_SHORT));
        assertFalse(has(result, RiskEvidenceType.METRIC_CLOSING));
        assertEquals(RiskLevel.CAUTION, result.level());
    }

    @Test void largeLivestockIsOnlySecondaryNearCorridor() {
        BoundingBox near = box(205,550,30,80);
        ObjectRiskAssessment horse = single(near,ObjectClass.HORSE);
        ObjectRiskAssessment dog = single(near,ObjectClass.DOG);
        assertTrue(has(horse,RiskEvidenceType.LARGE_HAZARD_CLASS));
        assertFalse(has(dog,RiskEvidenceType.LARGE_HAZARD_CLASS));
        assertEquals(RiskLevel.CAUTION,horse.level());
        assertEquals(RiskLevel.NORMAL,dog.level());
        assertTrue(horse.engineeringScore() > dog.engineeringScore());
        assertFalse(horse.level() == RiskLevel.CRITICAL);
    }

    @Test void sizePriorMetricTtcCannotAloneAuthorizeCritical() {
        TrackingSnapshot t = snap(3*S,track(7,ObjectClass.HORSE,TrackState.CONFIRMED,0,
                obs(3*S,box(640,550,70,80))));
        TtcEstimate metricTtc = TtcEstimate.of(1.5,TtcMethod.METRIC_RANGE,EvidenceQuality.MEDIUM,3*S);
        PhysicalObjectEstimate size = new PhysicalObjectEstimate(7,ObjectClass.HORSE,
                TrackState.CONFIRMED,3*S,
                DistanceEstimate.bounded(12,10,15,DistanceMethod.OBJECT_SIZE,EvidenceQuality.MEDIUM,3*S),
                RangeRateEstimate.of(-8,0,3,EvidenceQuality.MEDIUM,3*S),metricTtc,
                TtcEstimate.unavailable(3*S,PhysicalReason.INSUFFICIENT_HISTORY),metricTtc);
        RoadRiskSnapshot result = ENGINE.evaluate(t,path(t),with(t,size));
        assertTrue(result.available());
        assertFalse(result.highestLevel().orElseThrow() == RiskLevel.CRITICAL);
        assertTrue(has(result.objects().get(0),RiskEvidenceType.METRIC_TTC_SHORT));
    }
}
