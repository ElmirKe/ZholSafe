package kz.zholsafe.risk;

import kz.zholsafe.config.RiskConfig;
import kz.zholsafe.driver.DriverState;
import kz.zholsafe.driver.HeadPose;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Estimate;
import kz.zholsafe.model.EstimationMethod;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.tracking.MovementClass;
import kz.zholsafe.tracking.TrackedObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for {@link RiskEngine}. They run against {@link BaselineRiskEngine} today and
 * MUST keep passing against the Stage 4 engine: no camera, network, database or model involved.
 */
class RiskEngineContractTest {

    private static final int W = 1280;
    private static final int H = 720;

    private RiskEngine engine;
    private RiskConfig config;

    @BeforeEach
    void setUp() {
        config = RiskConfig.defaults();
        engine = new BaselineRiskEngine(config);
    }

    @Test
    void emptyInputIsNormal() {
        RiskAssessment a = engine.evaluate(input(List.of(), DriverState.unavailable(1L)));
        assertEquals(RiskLevel.NORMAL, a.level());
        assertEquals(0f, a.totalRisk(), 1e-6);
    }

    @Test
    void detectionAloneIsNotCritical() {
        // High-confidence horse, OUTSIDE the corridor, no trajectory, no distance, no TTC.
        TrackedObject horse = track(1, ObjectClass.HORSE, 0.97f, box(50, 300, 200, 450), false,
                MovementClass.UNKNOWN, Estimate.unavailable(), Estimate.unavailable());
        RiskAssessment a = engine.evaluate(input(List.of(horse), alertDriver()));
        assertNotEquals(RiskLevel.CRITICAL, a.level(), "detection != risk");
        assertTrue(a.reasons().contains(RiskReason.HORSE_DETECTED));
    }

    @Test
    void nonNormalAlwaysHasReasons() {
        TrackedObject horse = track(1, ObjectClass.HORSE, 0.9f, box(500, 300, 800, 700), true,
                MovementClass.CLOSING, Estimate.unavailable(), Estimate.unavailable());
        RiskAssessment a = engine.evaluate(input(List.of(horse), alertDriver()));
        assertTrue(a.level().isAtLeast(RiskLevel.CAUTION));
        assertFalse(a.reasons().isEmpty());
    }

    @Test
    void combinedSignalsEscalateToCritical() {
        TrackedObject horse = track(1, ObjectClass.HORSE, 0.94f, box(500, 300, 800, 700), true,
                MovementClass.APPROACHING_CORRIDOR,
                Estimate.of(12.0, EstimationMethod.MONOCULAR_UNCALIBRATED),
                Estimate.of(1.8, EstimationMethod.MONOCULAR_UNCALIBRATED));
        DriverState drowsy = new DriverState(true, true, 2000L, true, 0.4f, HeadPose.UNAVAILABLE,
                false, 0.9f, 1L);
        RiskAssessment a = engine.evaluate(input(List.of(horse), drowsy));
        assertEquals(RiskLevel.CRITICAL, a.level());
        assertTrue(a.reasons().containsAll(List.of(
                RiskReason.DRIVER_EYES_CLOSED,
                RiskReason.HORSE_DETECTED,
                RiskReason.OBJECT_APPROACHING_DRIVING_CORRIDOR,
                RiskReason.LOW_ESTIMATED_TTC)), "reasons were: " + a.reasons());
    }

    @Test
    void drowsyDriverAloneRaisesDriverRiskWithReason() {
        DriverState drowsy = new DriverState(true, true, 2500L, false, Float.NaN, HeadPose.UNAVAILABLE,
                false, 0.9f, 1L);
        RiskAssessment a = engine.evaluate(input(List.of(), drowsy));
        assertTrue(a.driverRisk() > 0f);
        assertTrue(a.reasons().contains(RiskReason.DRIVER_PROLONGED_EYE_CLOSURE));
        assertTrue(a.level().isAtLeast(RiskLevel.CAUTION));
    }

    @Test
    void lowConfidenceTracksDoNotRaiseRisk() {
        TrackedObject ghost = track(1, ObjectClass.PERSON, config.minConfidenceForRisk() - 0.05f,
                box(500, 300, 800, 700), true, MovementClass.CLOSING, Estimate.unavailable(), Estimate.unavailable());
        RiskAssessment a = engine.evaluate(input(List.of(ghost), alertDriver()));
        assertEquals(0f, a.roadRisk(), 1e-6);
        assertEquals(RiskLevel.NORMAL, a.level());
    }

    @Test
    void isDeterministic() {
        TrackedObject cow = track(3, ObjectClass.COW, 0.8f, box(400, 300, 700, 600), true,
                MovementClass.IN_CORRIDOR, Estimate.unavailable(), Estimate.unavailable());
        RiskInput in = input(List.of(cow), alertDriver());
        assertEquals(engine.evaluate(in), engine.evaluate(in));
    }

    @Test
    void unavailableDistanceAndTtcNeverProduceCollisionReasons() {
        TrackedObject sheep = track(2, ObjectClass.SHEEP, 0.9f, box(400, 300, 700, 600), true,
                MovementClass.STATIONARY, Estimate.unavailable(), Estimate.unavailable());
        RiskAssessment a = engine.evaluate(input(List.of(sheep), alertDriver()));
        assertFalse(a.reasons().contains(RiskReason.LOW_ESTIMATED_TTC));
        assertFalse(a.reasons().contains(RiskReason.LOW_ESTIMATED_DISTANCE));
    }

    @Test
    void riskDetectorUnavailableIsReported() {
        RiskInput in = new RiskInput(List.of(), alertDriver(), VehicleContext.UNKNOWN, false, W, H, 1L);
        RiskAssessment a = engine.evaluate(in);
        assertTrue(a.reasons().contains(RiskReason.ROAD_DETECTOR_UNAVAILABLE));
    }

    @Test
    void assessmentRejectsUnexplainedNonNormalLevel() {
        assertThrows(IllegalArgumentException.class,
                () -> new RiskAssessment(0f, 0f, 0f, 0.9f, RiskLevel.CRITICAL, List.of(), 1L));
    }

    @Test
    void levelThresholdsAreMonotonic() {
        RiskConfig.LevelThresholds t = config.levelThresholds();
        assertEquals(RiskLevel.NORMAL, t.levelFor(0f));
        assertEquals(RiskLevel.CAUTION, t.levelFor(t.caution()));
        assertEquals(RiskLevel.WARNING, t.levelFor(t.warning()));
        assertEquals(RiskLevel.CRITICAL, t.levelFor(t.critical()));
        assertThrows(IllegalArgumentException.class, () -> new RiskConfig.LevelThresholds(0.8f, 0.5f, 0.2f));
    }

    // ---- helpers ----

    private static RiskInput input(List<TrackedObject> tracks, DriverState driver) {
        return new RiskInput(tracks, driver, VehicleContext.UNKNOWN, true, W, H, 1L);
    }

    private static DriverState alertDriver() {
        return new DriverState(true, false, 0L, true, 0.05f, HeadPose.UNAVAILABLE, false, 0.95f, 1L);
    }

    private static BoundingBox box(float x1, float y1, float x2, float y2) {
        return new BoundingBox(x1, y1, x2, y2);
    }

    private static TrackedObject track(int id, ObjectClass c, float conf, BoundingBox b, boolean inCorridor,
                                       MovementClass m, Estimate dist, Estimate ttc) {
        return new TrackedObject(id, c, conf, b, List.of(b.center()), m, dist, ttc, inCorridor, 5, 1L);
    }
}
