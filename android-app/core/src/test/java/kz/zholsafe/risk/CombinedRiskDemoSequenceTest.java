package kz.zholsafe.risk;

import kz.zholsafe.config.CombinedRiskConfig;
import kz.zholsafe.config.DriverGuardConfig;
import kz.zholsafe.driver.DriverObservation;
import kz.zholsafe.driver.DriverState;
import kz.zholsafe.driver.EyeState;
import kz.zholsafe.driver.HeadPose;
import kz.zholsafe.driver.SyntheticDriverObservationProvider;
import kz.zholsafe.pipeline.DriverGuardProcessor;
import org.junit.jupiter.api.Test;

import java.util.NavigableMap;
import java.util.TreeMap;

import static kz.zholsafe.driver.DriverGuardTestSupport.faceFull;
import static kz.zholsafe.driver.DriverGuardTestSupport.frameAt;
import static kz.zholsafe.driver.DriverGuardTestSupport.t;
import static kz.zholsafe.risk.CombinedRiskTestSupport.road;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Required Stage 4.3 deterministic demo sequence (also the synthetic demo provider acceptance
 * test). The DRIVER side runs entirely through the production chain
 * (SyntheticDriverObservationProvider → DriverGuardProcessor → TemporalDriverStateAnalyzer →
 * DriverRiskEngine); the ROAD side provides genuine {@link RoadRiskSnapshot} values per phase;
 * fusion runs the production {@link CombinedRiskEngine}. No final risk output is fabricated.
 *
 * <p>Timeline (driver source time, seconds — EXPERIMENTAL DEMO THRESHOLDS from
 * {@link DriverGuardConfig#defaults()}, warning closure 1.5 s, critical 3.0 s):
 * T0=1.0 normal/normal · T1=2.0 eyes close · T2=3.0 closure continues (CAUTION) ·
 * T3=3.5 prolonged threshold crossed (driver WARNING) · T4=3.6 road hazard (CAUTION) ·
 * T5=3.8 road WARNING · T6=4.0 driver WARNING + road WARNING ⇒ COMBINED CRITICAL with
 * COMBINED_HAZARD_ESCALATION. This is an engineering severity demo — no collision probability.
 */
class CombinedRiskDemoSequenceTest {

    private static final HeadPose FORWARD = HeadPose.of(0f, 0f, 0f);

    private final SyntheticDriverObservationProvider provider;
    private final DriverGuardProcessor driver;
    private final CombinedRiskProcessor combined;

    private long cursor;

    CombinedRiskDemoSequenceTest() {
        NavigableMap<Long, DriverObservation> script = new TreeMap<>();
        script.put(t(0.0), faceFull(t(0.0), 0.9f, 0.05f, FORWARD)); // normal driver
        script.put(t(2.0), faceFull(t(2.0), 0.0f, 0.05f, FORWARD)); // eyes close at T1
        provider = new SyntheticDriverObservationProvider("demo-script", script);
        driver = new DriverGuardProcessor(provider, DriverGuardConfig.defaults());
        combined = new CombinedRiskProcessor(CombinedRiskConfig.defaults());
        cursor = t(0.0);
    }

    /** Feeds driver frames at 0.1 s source-time cadence through the production processor. */
    private void feedDriverUntil(double seconds) throws Exception {
        long target = t(seconds);
        for (long ts = cursor; ts <= target; ts += 100_000_000L) {
            driver.process(frameAt(ts));
        }
        cursor = target + 100_000_000L;
    }

    private static RoadRiskSnapshot roadFor(double seconds, long ts) {
        RiskLevel level = seconds < 3.6 ? RiskLevel.NORMAL : seconds < 3.8 ? RiskLevel.CAUTION : RiskLevel.WARNING;
        return road(ts, level);
    }

    private CombinedRiskSnapshot evaluateAt(double seconds) {
        long ts = t(seconds);
        combined.updateRoad(roadFor(seconds, ts));
        combined.updateDriver(driver.latestRisk());
        return combined.evaluate();
    }

    @Test
    void deterministicEscalationSequence() throws Exception {
        // T0: driver normal, road normal, combined NORMAL.
        feedDriverUntil(1.0);
        DriverState s0 = driver.latestState();
        assertEquals(EyeState.OPEN, s0.eyeState(), "T0: driver eyes open");
        assertEquals(RiskLevel.NORMAL, driver.latestRisk().level().orElseThrow(), "T0: driver NORMAL");
        CombinedRiskSnapshot c0 = evaluateAt(1.0);
        assertEquals(CombinedRiskSnapshot.Status.READY, c0.status());
        assertEquals(RiskLevel.NORMAL, c0.combinedLevel().orElseThrow(), "T0: combined NORMAL");

        // T1: eyes close — closure just started, still NORMAL (a blink-in-progress is nothing).
        feedDriverUntil(2.0);
        assertEquals(EyeState.CLOSED, driver.latestState().eyeState(), "T1: eyes closed");
        assertEquals(RiskLevel.NORMAL, driver.latestRisk().level().orElseThrow(),
                "T1: closure just started must not escalate");

        // T2: closure continues (1.0 s) → driver CAUTION (weak persistent evidence).
        feedDriverUntil(3.0);
        assertEquals(1_000_000_000L, driver.latestState().continuousEyeClosureNanos(),
                "T2: source-time closure duration");
        assertEquals(RiskLevel.CAUTION, driver.latestRisk().level().orElseThrow(), "T2: driver CAUTION");
        assertEquals(RiskLevel.CAUTION, evaluateAt(3.0).combinedLevel().orElseThrow(),
                "T2: road still NORMAL ⇒ combined CAUTION");

        // T3: prolonged closure threshold (1.5 s) crossed → driver WARNING.
        feedDriverUntil(3.5);
        DriverRiskSnapshot d3 = driver.latestRisk();
        assertEquals(RiskLevel.WARNING, d3.level().orElseThrow(), "T3: driver WARNING");
        assertTrue(d3.reasons().contains(DriverRiskReason.PROLONGED_EYE_CLOSURE));

        // T4: road hazard appears (CAUTION) — driver WARNING + road CAUTION ⇒ WARNING, not CRITICAL.
        feedDriverUntil(3.6);
        CombinedRiskSnapshot c4 = evaluateAt(3.6);
        assertEquals(RiskLevel.CAUTION, c4.roadLevel().orElseThrow(), "T4: road CAUTION preserved");
        assertEquals(RiskLevel.WARNING, c4.combinedLevel().orElseThrow(),
                "T4: WARNING + CAUTION must stay WARNING");
        assertFalse(c4.reasons().contains(CombinedRiskReason.COMBINED_HAZARD_ESCALATION));
        assertTrue(c4.reasons().contains(CombinedRiskReason.DRIVER_IMPAIRMENT_WITH_ROAD_HAZARD));

        // T5: road risk rises to WARNING.
        feedDriverUntil(3.8);
        CombinedRiskSnapshot c5 = evaluateAt(3.8);
        assertEquals(RiskLevel.WARNING, c5.roadLevel().orElseThrow(), "T5: road WARNING");
        assertEquals(RiskLevel.CRITICAL, c5.combinedLevel().orElseThrow(),
                "T5: driver WARNING + road WARNING ⇒ combined CRITICAL");

        // T6: stable CRITICAL with the explicit interaction reason; components preserved.
        feedDriverUntil(4.0);
        assertEquals(RiskLevel.WARNING, driver.latestRisk().level().orElseThrow(),
                "T6: driver is still WARNING (closure 2.0 s < 3.0 s critical threshold)");
        assertFalse(driver.latestState().perclos().available(),
                "T6: the PERCLOS window is too young — honestly unavailable, not 0");
        CombinedRiskSnapshot c6 = evaluateAt(4.0);
        assertEquals(RiskLevel.CRITICAL, c6.combinedLevel().orElseThrow(), "T6: combined CRITICAL");
        assertEquals(CombinedRiskSnapshot.Status.READY, c6.status());
        assertTrue(c6.reasons().contains(CombinedRiskReason.COMBINED_HAZARD_ESCALATION),
                "T6: escalation must be explicitly explained");
        assertEquals(RiskLevel.WARNING, c6.roadLevel().orElseThrow(), "road component preserved");
        assertEquals(RiskLevel.WARNING, c6.driverLevel().orElseThrow(), "driver component preserved");
        assertEquals(t(4.0), c6.evaluationTimestampNanos(), "evaluation on the source-time reference");

        // Determinism: replaying the same feed produces the identical final snapshot.
        CombinedRiskDemoSequenceTest replay = new CombinedRiskDemoSequenceTest();
        replay.feedDriverUntil(4.0);
        assertEquals(c6.driverRisk(), replay.driver.latestRisk(), "deterministic driver chain");
    }
}
