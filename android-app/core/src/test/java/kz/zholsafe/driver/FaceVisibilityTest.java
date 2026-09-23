package kz.zholsafe.driver;

import kz.zholsafe.config.DriverGuardConfig;
import kz.zholsafe.risk.DriverRiskEngine;
import kz.zholsafe.risk.DriverRiskReason;
import kz.zholsafe.risk.DriverRiskSnapshot;
import kz.zholsafe.risk.RiskLevel;
import org.junit.jupiter.api.Test;

import static kz.zholsafe.driver.DriverGuardTestSupport.faceEyes;
import static kz.zholsafe.driver.DriverGuardTestSupport.faceNoEyes;
import static kz.zholsafe.driver.DriverGuardTestSupport.noFace;
import static kz.zholsafe.driver.DriverGuardTestSupport.t;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Required Stage 4.3 face-visibility tests: FACE_NOT_DETECTED must never mean DRIVER_ASLEEP —
 * one missing frame is nothing, persistent loss is monitoring/visibility evidence only.
 */
class FaceVisibilityTest {

    private final DriverGuardConfig config = DriverGuardConfig.defaults();
    private final TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(config);
    private final DriverRiskEngine risk = new DriverRiskEngine(config);

    @Test
    void oneMissingFaceFrameProducesNoDrowsinessDiagnosis() {
        // An on-going closure must NOT silently continue across missing face data
        // (missing != closed). After the face returns, a fresh run starts.
        analyzer.update(faceEyes(t(0.0), 0.0f));
        analyzer.update(faceEyes(t(0.5), 0.0f));
        DriverState missing = analyzer.update(noFace(t(0.6)));
        assertFalse(missing.faceDetected());
        assertEquals(EyeState.UNKNOWN, missing.eyeState(), "missing face means unknown eyes");
        assertEquals(0L, missing.continuousEyeClosureNanos(),
                "missing face must break, not continue, the closure run");
        assertEquals(0L, missing.continuousFaceLossNanos(), "face-loss run starts now");

        DriverRiskSnapshot snapshot = risk.evaluate(missing);
        assertTrue(snapshot.reasons().contains(DriverRiskReason.FACE_NOT_DETECTED),
                "informative visibility reason is expected");
        assertFalse(snapshot.level().orElseThrow().isAtLeast(RiskLevel.CAUTION),
                "one missing-face frame must not escalate");
        assertFalse(snapshot.reasons().contains(DriverRiskReason.PROLONGED_EYE_CLOSURE));

        DriverState back = analyzer.update(faceEyes(t(0.7), 0.9f));
        assertEquals(EyeState.OPEN, back.eyeState());
        assertEquals(0L, back.continuousFaceLossNanos());
    }

    @Test
    void persistentFaceLossIsVisibilityEvidenceOnly() {
        // Experimental demo threshold: persistentFaceLossSeconds = 5.0 s.
        DriverState state = analyzer.current();
        for (long ts = t(0.0); ts <= t(4.9); ts += 500_000_000L) {
            state = analyzer.update(noFace(ts));
            assertEquals(RiskLevel.NORMAL, risk.evaluate(state).level().orElseThrow(),
                    "short face loss must not strongly escalate");
        }
        state = analyzer.update(noFace(t(5.0)));
        assertEquals(5_000_000_000L, state.continuousFaceLossNanos());
        DriverRiskSnapshot snapshot = risk.evaluate(state);
        assertTrue(snapshot.reasons().contains(DriverRiskReason.DRIVER_VISIBILITY_LOST));
        assertTrue(snapshot.reasons().contains(DriverRiskReason.FACE_NOT_DETECTED));
        assertFalse(snapshot.reasons().contains(DriverRiskReason.PROLONGED_EYE_CLOSURE));
        assertFalse(snapshot.reasons().contains(DriverRiskReason.HIGH_PERCLOS));
        assertFalse(snapshot.reasons().contains(DriverRiskReason.EYES_CLOSED));
        assertEquals(RiskLevel.CAUTION, snapshot.level().orElseThrow(),
                "visibility loss is CAUTION monitoring evidence, never a drowsiness verdict");
    }

    @Test
    void facePresentButEyesUnavailableMeansUnknownEyes() {
        DriverState state = analyzer.update(faceNoEyes(t(0.0)));
        assertTrue(state.faceDetected());
        assertEquals(EyeState.UNKNOWN, state.eyeState());
        assertEquals(0L, state.continuousEyeClosureNanos(),
                "eyes-not-evaluable is not closed time");
        assertEquals(ObservationQuality.DEGRADED, state.observationQuality());
        assertEquals(RiskLevel.NORMAL, risk.evaluate(state).level().orElseThrow(),
                "one such frame must not escalate");

        // Persistence turns it into explicit INSUFFICIENT_EYE_VISIBILITY monitoring evidence.
        for (long ts = t(0.5); ts <= t(5.0); ts += 500_000_000L) {
            state = analyzer.update(faceNoEyes(ts));
        }
        assertEquals(5_000_000_000L, state.continuousEyeUnavailableNanos());
        DriverRiskSnapshot snapshot = risk.evaluate(state);
        assertTrue(snapshot.reasons().contains(DriverRiskReason.INSUFFICIENT_EYE_VISIBILITY));
        assertFalse(snapshot.reasons().contains(DriverRiskReason.EYES_CLOSED));
        assertEquals(RiskLevel.CAUTION, snapshot.level().orElseThrow());
    }
}
