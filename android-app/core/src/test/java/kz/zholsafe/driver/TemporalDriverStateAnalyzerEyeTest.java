package kz.zholsafe.driver;

import kz.zholsafe.config.DriverGuardConfig;
import kz.zholsafe.risk.DriverRiskEngine;
import kz.zholsafe.risk.DriverRiskReason;
import kz.zholsafe.risk.DriverRiskSnapshot;
import kz.zholsafe.risk.RiskLevel;
import org.junit.jupiter.api.Test;

import static kz.zholsafe.driver.DriverGuardTestSupport.faceEyes;
import static kz.zholsafe.driver.DriverGuardTestSupport.t;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Required Stage 4.3 eye tests: source-time closure timing, blink vs prolonged closure,
 * reopening, irregular frame intervals, duplicate/reversed timestamps and gaps.
 */
class TemporalDriverStateAnalyzerEyeTest {

    private final DriverGuardConfig config = DriverGuardConfig.defaults();
    private final TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(config);
    private final DriverRiskEngine risk = new DriverRiskEngine(config);

    private DriverState closedRun(double toSeconds) {
        DriverState state = analyzer.current();
        for (long ts = t(0); ts <= t(toSeconds); ts += 100_000_000L) {
            state = analyzer.update(faceEyes(ts, DriverGuardTestSupport.CLOSED));
        }
        return state;
    }

    private static DriverObservation faceEyesIndependently(long ts, float left, float right) {
        return new DriverObservation(ts, true, true, left, right,
                false, Float.NaN, HeadPose.UNAVAILABLE, 0.95f);
    }

    @Test
    void leftEyeClosedRightEyeOpenIsNotBilateralClosure() {
        DriverState state = analyzer.update(faceEyesIndependently(t(0.0), 0.0f, 0.9f));

        assertEquals(EyeState.OPEN, state.eyeState());
        assertEquals(0L, state.continuousEyeClosureNanos());
    }

    @Test
    void rightEyeClosedLeftEyeOpenIsNotBilateralClosure() {
        DriverState state = analyzer.update(faceEyesIndependently(t(0.0), 0.9f, 0.0f));

        assertEquals(EyeState.OPEN, state.eyeState());
        assertEquals(0L, state.continuousEyeClosureNanos());
    }

    @Test
    void bothEyesClosedProduceBilateralClosure() {
        DriverState state = analyzer.update(faceEyesIndependently(t(0.0), 0.0f, 0.0f));

        assertEquals(EyeState.CLOSED, state.eyeState());
    }

    @Test
    void sustainedUnilateralClosureDoesNotProduceProlongedClosureWarning() {
        DriverState state = analyzer.current();
        for (long ts = t(0.0); ts <= t(1.6); ts += 100_000_000L) {
            state = analyzer.update(faceEyesIndependently(ts, 0.0f, 0.9f));
        }

        assertEquals(EyeState.OPEN, state.eyeState());
        assertEquals(0L, state.continuousEyeClosureNanos());
        DriverRiskSnapshot snapshot = risk.evaluate(state);
        assertFalse(snapshot.level().orElseThrow().isAtLeast(RiskLevel.WARNING));
        assertFalse(snapshot.reasons().contains(DriverRiskReason.PROLONGED_EYE_CLOSURE));
    }

    @Test
    void openEyesProduceNormal() {
        DriverState state = DriverGuardTestSupport.feed(analyzer, 0, 2, ts -> faceEyes(ts, 0.9f));
        assertEquals(EyeState.OPEN, state.eyeState());
        assertEquals(0L, state.continuousEyeClosureNanos());
        DriverRiskSnapshot snapshot = risk.evaluate(state);
        assertEquals(RiskLevel.NORMAL, snapshot.level().orElseThrow());
    }

    @Test
    void shortBlinkNeverReachesWarningOrCritical() {
        // A ~0.2 s blink: two closed observations, then reopen.
        analyzer.update(faceEyes(t(0.0), DriverGuardTestSupport.OPEN));
        DriverState blink1 = analyzer.update(faceEyes(t(0.1), DriverGuardTestSupport.CLOSED));
        DriverState blink2 = analyzer.update(faceEyes(t(0.2), DriverGuardTestSupport.CLOSED));
        DriverState reopen = analyzer.update(faceEyes(t(0.3), DriverGuardTestSupport.OPEN));

        assertEquals(0L, blink1.continuousEyeClosureNanos(), "run starts at first closed sample");
        assertEquals(100_000_000L, blink2.continuousEyeClosureNanos());
        for (DriverState s : new DriverState[] { blink1, blink2, reopen }) {
            DriverRiskSnapshot snapshot = risk.evaluate(s);
            assertFalse(snapshot.level().orElseThrow().isAtLeast(RiskLevel.WARNING),
                    "a normal blink must not escalate, got " + snapshot.level());
            assertFalse(snapshot.reasons().contains(DriverRiskReason.PROLONGED_EYE_CLOSURE));
        }
        assertEquals(EyeState.OPEN, reopen.eyeState());
        assertEquals(0L, reopen.continuousEyeClosureNanos(), "reopening resets the closure run");
    }

    @Test
    void prolongedClosureEscalatesThroughConfiguredThresholds() {
        // Experimental demo thresholds: caution 0.7 s, warning 1.5 s, critical 3.0 s.
        DriverState atCaution = closedRun(0.8);
        assertEquals(EyeState.CLOSED, atCaution.eyeState());
        assertEquals(800_000_000L, atCaution.continuousEyeClosureNanos());
        DriverRiskSnapshot caution = risk.evaluate(atCaution);
        assertEquals(RiskLevel.CAUTION, caution.level().orElseThrow());
        assertTrue(caution.reasons().contains(DriverRiskReason.PROLONGED_EYE_CLOSURE));

        DriverState atWarning = closedRun(1.6);
        assertEquals(1_600_000_000L, atWarning.continuousEyeClosureNanos());
        assertEquals(RiskLevel.WARNING, risk.evaluate(atWarning).level().orElseThrow());

        DriverState atCritical = closedRun(3.1);
        assertEquals(RiskLevel.CRITICAL, risk.evaluate(atCritical).level().orElseThrow());
    }

    @Test
    void reopenResetsContinuousClosure() {
        closedRun(1.0);
        DriverState open = analyzer.update(faceEyes(t(1.1), DriverGuardTestSupport.OPEN));
        assertEquals(0L, open.continuousEyeClosureNanos());

        DriverState closedAgain = analyzer.update(faceEyes(t(1.2), DriverGuardTestSupport.CLOSED));
        assertEquals(0L, closedAgain.continuousEyeClosureNanos(),
                "a reopened run must restart from the new source timestamp");
    }

    @Test
    void irregularFrameIntervalsUseSourceTimeNotFrameCount() {
        // 6 closed samples at irregular instants (all gaps ≤ 1.0 s keep continuity).
        double[] times = { 0.0, 0.45, 1.05, 1.80, 2.55, 3.40 };
        DriverState state = analyzer.current();
        for (double seconds : times) {
            state = analyzer.update(faceEyes(t(seconds), DriverGuardTestSupport.CLOSED));
        }
        assertEquals(3_400_000_000L, state.continuousEyeClosureNanos(),
                "duration must be last source timestamp minus closure start");
        // Frame-count-based logic would claim 6 "frames"; source time is 3.4 s ≥ critical 3.0 s.
        assertEquals(RiskLevel.CRITICAL, risk.evaluate(state).level().orElseThrow());
    }

    @Test
    void duplicateTimestampExplicitlyRejected() {
        closedRun(0.5);
        DriverState before = analyzer.current();
        DriverState duplicate = analyzer.update(faceEyes(t(0.5), DriverGuardTestSupport.CLOSED));

        assertEquals(DriverState.TimestampRejection.DUPLICATE_TIMESTAMP, duplicate.timestampRejection());
        assertEquals(before.timestampNanos(), duplicate.timestampNanos(), "accepted time must not move");
        assertEquals(before.continuousEyeClosureNanos(), duplicate.continuousEyeClosureNanos());

        DriverState next = analyzer.update(faceEyes(t(0.6), DriverGuardTestSupport.CLOSED));
        assertEquals(DriverState.TimestampRejection.NONE, next.timestampRejection());
        assertEquals(600_000_000L, next.continuousEyeClosureNanos(),
                "timing continues from the last accepted observation, not the duplicate");
    }

    @Test
    void reversedTimestampExplicitlyRejected() {
        analyzer.update(faceEyes(t(1.0), DriverGuardTestSupport.CLOSED));
        DriverState reversed = analyzer.update(faceEyes(t(0.7), DriverGuardTestSupport.CLOSED));

        assertEquals(DriverState.TimestampRejection.REVERSED_TIMESTAMP, reversed.timestampRejection());
        assertEquals(t(1.0), reversed.timestampNanos(), "the accepted timeline must not rewind");
        assertEquals(0L, reversed.continuousEyeClosureNanos());
    }

    @Test
    void sourceTimeGapBreaksClosureContinuityWithoutFabricatingClosedTime() {
        analyzer.update(faceEyes(t(0.0), DriverGuardTestSupport.CLOSED));
        // 2.0 s gap > maximumObservationGapSeconds (1.0 s): the bridge time is unknown, not closed.
        DriverState after = analyzer.update(faceEyes(t(2.0), DriverGuardTestSupport.CLOSED));
        assertEquals(0L, after.continuousEyeClosureNanos(),
                "closure run restarts after a source-time gap (unknown is not closed)");
        assertEquals(RiskLevel.NORMAL, risk.evaluate(after).level().orElseThrow());
    }

    @Test
    void partiallyClosedIsNotClosed() {
        DriverState state = analyzer.update(faceEyes(t(0.0), 0.45f)); // between 0.30 and 0.60
        assertEquals(EyeState.PARTIALLY_CLOSED, state.eyeState());
        assertEquals(0L, state.continuousEyeClosureNanos());
        assertEquals(RiskLevel.NORMAL, risk.evaluate(state).level().orElseThrow());
    }
}
