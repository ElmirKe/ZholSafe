package kz.zholsafe.risk;

import kz.zholsafe.config.DriverGuardConfig;
import kz.zholsafe.driver.DriverGuardTestSupport;
import kz.zholsafe.driver.DriverState;
import kz.zholsafe.driver.EyeState;
import kz.zholsafe.driver.HeadPoseState;
import kz.zholsafe.driver.HeadPose;
import kz.zholsafe.driver.ObservationQuality;
import kz.zholsafe.driver.PerclosValue;
import kz.zholsafe.driver.TemporalDriverStateAnalyzer;
import kz.zholsafe.driver.YawnLikeState;
import org.junit.jupiter.api.Test;

import static kz.zholsafe.driver.DriverGuardTestSupport.faceEyes;
import static kz.zholsafe.driver.DriverGuardTestSupport.faceEyesLowConfidence;
import static kz.zholsafe.driver.DriverGuardTestSupport.t;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Required Stage 4.3 driver-risk policy tests. The engine reads ONLY {@link DriverState}
 * (see {@link DriverRiskEvaluator}) — road classes (HORSE/COW/DOG), TTC and corridor data are
 * structurally unable to enter it; that separation is enforced by the type signature.
 */
class DriverRiskEngineTest {

    private static final long TS = t(0.0);

    private final DriverGuardConfig config = DriverGuardConfig.defaults();
    private final DriverRiskEngine engine = new DriverRiskEngine(config);

    /** Coherent fabricated state for engine-level policy tests. */
    private static DriverState state(long closureNanos, float perclosAvailableValue,
                                     YawnLikeState yawn, long yawnNanos,
                                     HeadPoseState head, long headAwayNanos) {
        boolean perclosAvailable = !Float.isNaN(perclosAvailableValue);
        return new DriverState(TS, true,
                closureNanos > 0 ? EyeState.CLOSED : EyeState.OPEN,
                closureNanos,
                perclosAvailable
                        ? new PerclosValue(true, perclosAvailableValue, 40_000_000_000L, 60_000_000_000L)
                        : PerclosValue.unavailable(0L, 0L),
                head, headAwayNanos, HeadPose.UNAVAILABLE, yawn, yawnNanos, 0L, 0L,
                ObservationQuality.GOOD, 0.9f, DriverState.TimestampRejection.NONE);
    }

    private static DriverState closed(long closureNanos) {
        return new DriverState(TS, true, EyeState.CLOSED, Math.max(0L, closureNanos),
                PerclosValue.unavailable(0L, 0L), HeadPoseState.UNKNOWN, 0L, HeadPose.UNAVAILABLE,
                YawnLikeState.UNAVAILABLE, 0L, 0L, 0L, ObservationQuality.GOOD, 0.9f,
                DriverState.TimestampRejection.NONE);
    }

    @Test
    void oneBlinkIsNeverCritical() {
        DriverRiskSnapshot snapshot = engine.evaluate(closed(150_000_000L)); // 0.15 s blink
        assertEquals(RiskLevel.NORMAL, snapshot.level().orElseThrow(),
                "one blink must never produce high risk");
        assertTrue(snapshot.reasons().contains(DriverRiskReason.EYES_CLOSED),
                "informative closed-eye reason stays allowed at NORMAL");
        assertFalse(snapshot.reasons().contains(DriverRiskReason.PROLONGED_EYE_CLOSURE));
    }

    @Test
    void oneLowConfidenceClosedObservationProducesNoStrongRisk() {
        DriverGuardConfig cfg = DriverGuardConfig.defaults();
        TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(cfg);
        DriverRiskEngine engine = new DriverRiskEngine(cfg);
        DriverState state = analyzer.update(faceEyesLowConfidence(t(0.0), 0.0f));
        assertEquals(EyeState.UNKNOWN, state.eyeState(),
                "below the confidence minimum the eyes are not evaluable");
        state = analyzer.update(faceEyesLowConfidence(t(0.3), 0.0f));

        DriverRiskSnapshot snapshot = engine.evaluate(state);
        assertEquals(RiskLevel.NORMAL, snapshot.level().orElseThrow());
        assertFalse(snapshot.reasons().contains(DriverRiskReason.EYES_CLOSED));
        assertTrue(snapshot.reasons().contains(DriverRiskReason.LOW_OBSERVATION_QUALITY),
                "the quality problem stays visible as informative evidence");
        assertEquals(0L, state.continuousEyeClosureNanos(),
                "low-confidence frames must not accumulate closure time");
    }

    @Test
    void prolongedValidClosureEscalates() {
        assertEquals(RiskLevel.CAUTION,
                engine.evaluate(closed(800_000_000L)).level().orElseThrow());
        DriverRiskSnapshot warning = engine.evaluate(closed(1_600_000_000L));
        assertEquals(RiskLevel.WARNING, warning.level().orElseThrow());
        assertTrue(warning.reasons().contains(DriverRiskReason.PROLONGED_EYE_CLOSURE));
        assertEquals(RiskLevel.CRITICAL,
                engine.evaluate(closed(3_200_000_000L)).level().orElseThrow());
    }

    @Test
    void highPerclosPlusProlongedClosureMayStrengthenRisk() {
        // 60 s window, 40 s valid, PERCLOS 0.40 ≥ warning 0.30, closure 1.6 s ≥ warning 1.5 s.
        DriverRiskSnapshot strengthened = engine.evaluate(
                state(1_600_000_000L, 0.40f, YawnLikeState.NONE, 0L, HeadPoseState.FORWARD, 0L));
        assertEquals(RiskLevel.CRITICAL, strengthened.level().orElseThrow(),
                "sustained closure fraction together with on-going prolonged closure may reach CRITICAL");
        assertTrue(strengthened.reasons().containsAll(java.util.List.of(
                DriverRiskReason.PROLONGED_EYE_CLOSURE, DriverRiskReason.HIGH_PERCLOS)),
                "the strengthening is explained by both contributing reason codes");

        // Same closure with moderate PERCLOS stays WARNING (no CRITICAL from PERCLOS alone).
        assertEquals(RiskLevel.WARNING, engine.evaluate(
                state(1_600_000_000L, 0.20f, YawnLikeState.NONE, 0L, HeadPoseState.FORWARD, 0L))
                .level().orElseThrow());
        assertEquals(RiskLevel.WARNING, engine.evaluate(
                state(1_600_000_000L, Float.NaN, YawnLikeState.NONE, 0L, HeadPoseState.FORWARD, 0L))
                .level().orElseThrow(), "same rule without PERCLOS evidence");
        // High windowed PERCLOS alone (eyes currently open) must not be CRITICAL.
        assertEquals(RiskLevel.WARNING, engine.evaluate(
                state(0L, 0.40f, YawnLikeState.NONE, 0L, HeadPoseState.FORWARD, 0L))
                .level().orElseThrow());
    }

    @Test
    void combinedSingleFrameOrWeakEvidenceNeverReachesCritical() {
        // yawn-like + sustained looking-down + a fresh tiny closure: still not CRITICAL.
        assertEquals(RiskLevel.WARNING, engine.evaluate(
                state(50_000_000L, Float.NaN, YawnLikeState.YAWN_LIKE, 3_000_000_000L,
                        HeadPoseState.DOWN, 3_000_000_000L)).level().orElseThrow());
    }

    @Test
    void everyNonNormalDriverRiskCarriesStructuredReasons() {
        DriverRiskSnapshot caution = engine.evaluate(
                state(800_000_000L, Float.NaN, YawnLikeState.NONE, 0L, HeadPoseState.FORWARD, 0L));
        assertFalse(caution.reasons().isEmpty());
        DriverRiskSnapshot yawn = engine.evaluate(
                state(0L, Float.NaN, YawnLikeState.YAWN_LIKE, 2_500_000_000L, HeadPoseState.FORWARD, 0L));
        assertEquals(RiskLevel.CAUTION, yawn.level().orElseThrow());
        assertFalse(yawn.reasons().isEmpty());
    }

    @Test
    void evaluationIsDeterministic() {
        DriverState state = state(1_600_000_000L, 0.40f, YawnLikeState.NONE, 0L,
                HeadPoseState.FORWARD, 0L);
        assertEquals(engine.evaluate(state), engine.evaluate(state),
                "same state in — same snapshot out");
    }

    @Test
    void feedThroughAnalyzerKeepsPolicyIntact() {
        // End-to-end: the production analyzer path, not fabricated states.
        TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(config);
        DriverState state = analyzer.current();
        for (long ts = t(0); ts <= t(1.6); ts += 100_000_000L) {
            state = analyzer.update(faceEyes(ts, 0.0f));
        }
        DriverRiskSnapshot snapshot = engine.evaluate(state);
        assertEquals(RiskLevel.WARNING, snapshot.level().orElseThrow(),
                "1.6 s of source-time closure crosses the 1.5 s experimental warning threshold");
        assertEquals(DriverGuardTestSupport.t(1.6), snapshot.timestampNanos(),
                "the snapshot carries the driver source timestamp");
    }
}
