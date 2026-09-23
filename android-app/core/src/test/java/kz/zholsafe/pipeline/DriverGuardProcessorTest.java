package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;
import kz.zholsafe.config.DriverGuardConfig;
import kz.zholsafe.driver.DriverObservation;
import kz.zholsafe.driver.DriverObservationProvider;
import kz.zholsafe.driver.DriverState;
import kz.zholsafe.driver.SyntheticDriverObservationProvider;
import kz.zholsafe.risk.DriverRiskEngine;
import kz.zholsafe.risk.DriverRiskSnapshot;
import kz.zholsafe.risk.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.NavigableMap;
import java.util.TreeMap;

import static kz.zholsafe.driver.DriverGuardTestSupport.faceEyes;
import static kz.zholsafe.driver.DriverGuardTestSupport.frameAt;
import static kz.zholsafe.driver.DriverGuardTestSupport.t;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4.3 processor tests: bounded latest-snapshot publication, explicit failure degradation,
 * independence of the road pipeline ({@link RoadDetectionProcessor} is untouched) and the
 * synthetic-provider → production-chain wiring.
 */
class DriverGuardProcessorTest {

    private static DriverObservationProvider script(long at, DriverObservation template) {
        NavigableMap<Long, DriverObservation> script = new TreeMap<>();
        script.put(at, template.withTimestamp(at));
        return new SyntheticDriverObservationProvider(script);
    }

    @Test
    void publishesOnlyTheLatestBoundedSnapshot() throws Exception {
        DriverGuardProcessor processor =
                new DriverGuardProcessor(script(t(0.0), faceEyes(t(0.0), 0.9f)), DriverGuardConfig.defaults());
        assertEquals(DriverRiskSnapshot.Status.NOT_STARTED, processor.latestRisk().status(),
                "before the first frame, driver risk is NOT_STARTED (not fabricated NORMAL)");

        for (long ts = t(0.0); ts <= t(1.0); ts += 100_000_000L) {
            processor.process(frameAt(ts));
        }
        assertEquals(t(1.0), processor.latestRisk().timestampNanos(),
                "only the latest snapshot is retained — no queues");
        assertEquals(DriverRiskSnapshot.Status.READY, processor.latestRisk().status());
        assertEquals(RiskLevel.NORMAL, processor.latestRisk().level().orElseThrow());
        assertTrue(processor.statusLine().contains("synthetic-script"));
    }

    @Test
    void providerFailureDegradesExplicitlyNeverNormal() {
        DriverObservationProvider broken = new DriverObservationProvider() {
            @Override
            public String sourceId() {
                return "broken";
            }

            @Override
            public DriverObservation provide(Frame frame) {
                throw new IllegalStateException("backend exploded");
            }
        };
        DriverGuardProcessor processor = new DriverGuardProcessor(broken, DriverGuardConfig.defaults());
        assertThrows(IllegalStateException.class, () -> processor.process(frameAt(t(0.0))));
        assertEquals(DriverRiskSnapshot.Status.UNAVAILABLE, processor.latestRisk().status(),
                "failure publishes UNAVAILABLE, never a fabricated safe result");
        assertTrue(processor.latestRisk().level().isEmpty());
        assertEquals(DriverState.unavailable(t(0.0)), processor.latestState());
    }

    @Test
    void processObservationUsesTheSameProductionChain() {
        DriverGuardProcessor processor = new DriverGuardProcessor(
                script(t(0.0), faceEyes(t(0.0), 0.9f)),
                new kz.zholsafe.driver.TemporalDriverStateAnalyzer(DriverGuardConfig.defaults()),
                new DriverRiskEngine(DriverGuardConfig.defaults()));
        DriverRiskSnapshot risk = processor.processObservation(faceEyes(t(0.0), 0.9f));
        assertEquals(RiskLevel.NORMAL, risk.level().orElseThrow());
        assertEquals(risk, processor.latestRisk());
        assertEquals(t(0.0), processor.latestState().timestampNanos());
    }
}
