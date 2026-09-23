package kz.zholsafe.risk;

import kz.zholsafe.config.CombinedRiskConfig;
import kz.zholsafe.config.DriverGuardConfig;
import org.junit.jupiter.api.Test;

import static kz.zholsafe.risk.CombinedRiskTestSupport.TS;
import static kz.zholsafe.risk.CombinedRiskTestSupport.driver;
import static kz.zholsafe.risk.CombinedRiskTestSupport.road;
import static kz.zholsafe.risk.CombinedRiskTestSupport.roadNotStarted;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Required Stage 4.3 degraded-mode and freshness tests: one valid subsystem stays usable when
 * the other is unavailable/stale/skewed; stale WARNING/CRITICAL snapshots can never silently
 * drive the fused level; "both unavailable" is degraded — NOT NORMAL.
 */
class CombinedRiskFreshnessTest {

    private static final long S = 1_000_000_000L;

    private final DriverRiskEngine driverEngine = new DriverRiskEngine(DriverGuardConfig.defaults());

    @Test
    void driverUnavailablePreservesRoadWarning() {
        CombinedRiskEngine fusion = new CombinedRiskEngine(CombinedRiskConfig.defaults());
        CombinedRiskSnapshot combined = fusion.evaluate(
                road(TS, RiskLevel.WARNING), DriverRiskSnapshot.unavailable(TS));
        assertEquals(CombinedRiskSnapshot.Status.ROAD_ONLY, combined.status());
        assertEquals(RiskLevel.WARNING, combined.combinedLevel().orElseThrow());
        assertTrue(combined.reasons().contains(CombinedRiskReason.DRIVER_UNAVAILABLE));
        assertTrue(combined.reasons().contains(CombinedRiskReason.ROAD_HAZARD_PRESENT));
    }

    @Test
    void roadUnavailablePreservesDriverWarning() {
        CombinedRiskEngine fusion = new CombinedRiskEngine(CombinedRiskConfig.defaults());
        CombinedRiskSnapshot combined = fusion.evaluate(
                roadNotStarted(), driver(driverEngine, TS, RiskLevel.WARNING));
        assertEquals(CombinedRiskSnapshot.Status.DRIVER_ONLY, combined.status());
        assertEquals(RiskLevel.WARNING, combined.combinedLevel().orElseThrow());
        assertTrue(combined.reasons().contains(CombinedRiskReason.ROAD_UNAVAILABLE));
        assertTrue(combined.reasons().contains(CombinedRiskReason.DRIVER_RISK_PRESENT));
    }

    @Test
    void bothUnavailableIsDegradedNotNormal() {
        CombinedRiskEngine fusion = new CombinedRiskEngine(CombinedRiskConfig.defaults());
        CombinedRiskSnapshot combined = fusion.evaluate(roadNotStarted(), DriverRiskSnapshot.notStarted());
        assertEquals(CombinedRiskSnapshot.Status.UNAVAILABLE, combined.status());
        assertFalse(combined.available());
        assertTrue(combined.combinedLevel().isEmpty(), "no level — degraded must not look NORMAL");
        assertTrue(combined.reasons().contains(CombinedRiskReason.ROAD_UNAVAILABLE));
        assertTrue(combined.reasons().contains(CombinedRiskReason.DRIVER_UNAVAILABLE));
    }

    @Test
    void freshRoadAndFreshDriverAreFused() {
        CombinedRiskEngine fusion = new CombinedRiskEngine(CombinedRiskConfig.defaults());
        CombinedRiskSnapshot combined = fusion.evaluate(
                road(TS, RiskLevel.WARNING), driver(driverEngine, TS + 400_000_000L, RiskLevel.WARNING));
        assertEquals(CombinedRiskSnapshot.Status.READY, combined.status(),
                "0.4 s skew is within the 1 s demo budget — fusion must happen");
        assertEquals(RiskLevel.CRITICAL, combined.combinedLevel().orElseThrow());
    }

    @Test
    void staleDriverWarningNeverSilentlyFused() {
        // Skew budget 5 s, driver age budget 1 s.
        CombinedRiskEngine fusion = new CombinedRiskEngine(new CombinedRiskConfig(1.0d, 1.0d, 5.0d));
        CombinedRiskSnapshot combined = fusion.evaluate(
                road(TS + 10 * S, RiskLevel.WARNING),
                driver(driverEngine, TS + 8 * S, RiskLevel.CRITICAL)); // 2 s older ⇒ stale
        assertEquals(CombinedRiskSnapshot.Status.ROAD_ONLY, combined.status());
        assertTrue(combined.reasons().contains(CombinedRiskReason.STALE_DRIVER_STATE));
        assertEquals(RiskLevel.WARNING, combined.combinedLevel().orElseThrow(),
                "a stale driver CRITICAL must not influence the current level");
        assertEquals(RiskLevel.CRITICAL, combined.driverLevel().orElseThrow(),
                "stale component level stays visible as preserved information");
        assertFalse(combined.reasons().contains(CombinedRiskReason.COMBINED_HAZARD_ESCALATION));
    }

    @Test
    void staleRoadWarningNeverSilentlyFused() {
        CombinedRiskEngine fusion = new CombinedRiskEngine(new CombinedRiskConfig(1.0d, 1.0d, 5.0d));
        CombinedRiskSnapshot combined = fusion.evaluate(
                road(TS + 8 * S, RiskLevel.WARNING),
                driver(driverEngine, TS + 10 * S, RiskLevel.CAUTION));
        assertEquals(CombinedRiskSnapshot.Status.DRIVER_ONLY, combined.status());
        assertTrue(combined.reasons().contains(CombinedRiskReason.STALE_ROAD_STATE));
        assertEquals(RiskLevel.CAUTION, combined.combinedLevel().orElseThrow(),
                "a stale road WARNING must not influence the current level");
    }

    @Test
    void excessiveTimestampSkewDegradesToExplicitSingleSource() {
        // Age budgets 5 s each, skew budget 1 s: 4 s skew is too much to fuse.
        CombinedRiskEngine fusion = new CombinedRiskEngine(new CombinedRiskConfig(5.0d, 5.0d, 1.0d));
        CombinedRiskSnapshot combined = fusion.evaluate(
                road(TS + 10 * S, RiskLevel.WARNING),
                driver(driverEngine, TS + 6 * S, RiskLevel.CRITICAL));
        assertEquals(CombinedRiskSnapshot.Status.ROAD_ONLY, combined.status(),
                "the fresher component is preserved single-source");
        assertTrue(combined.reasons().contains(CombinedRiskReason.ROAD_DRIVER_TIMESTAMP_SKEW));
        assertTrue(combined.reasons().contains(CombinedRiskReason.STALE_DRIVER_STATE));
        assertEquals(RiskLevel.WARNING, combined.combinedLevel().orElseThrow());

        CombinedRiskSnapshot mirrored = fusion.evaluate(
                road(TS + 6 * S, RiskLevel.CAUTION),
                driver(driverEngine, TS + 10 * S, RiskLevel.WARNING));
        assertEquals(CombinedRiskSnapshot.Status.DRIVER_ONLY, mirrored.status());
        assertTrue(mirrored.reasons().contains(CombinedRiskReason.ROAD_DRIVER_TIMESTAMP_SKEW));
        assertTrue(mirrored.reasons().contains(CombinedRiskReason.STALE_ROAD_STATE));
        assertEquals(RiskLevel.WARNING, mirrored.combinedLevel().orElseThrow());
    }

    @Test
    void combinedRiskProcessorKeepsLatestOnlyAndFusesOnDemand() {
        CombinedRiskProcessor processor = new CombinedRiskProcessor(CombinedRiskConfig.defaults());
        assertEquals(CombinedRiskSnapshot.Status.UNAVAILABLE, processor.latest().status(),
                "before anything is published, fusion is degraded (not NORMAL)");

        processor.updateRoad(road(TS, RiskLevel.WARNING));
        processor.updateDriver(driver(driverEngine, TS, RiskLevel.NORMAL));
        CombinedRiskSnapshot combined = processor.evaluate();
        assertEquals(CombinedRiskSnapshot.Status.READY, combined.status());
        assertEquals(RiskLevel.WARNING, combined.combinedLevel().orElseThrow());
        assertEquals(combined, processor.latest());
    }
}
