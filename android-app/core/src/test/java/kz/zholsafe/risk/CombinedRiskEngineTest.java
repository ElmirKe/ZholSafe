package kz.zholsafe.risk;

import kz.zholsafe.config.CombinedRiskConfig;
import kz.zholsafe.config.DriverGuardConfig;
import org.junit.jupiter.api.Test;

import static kz.zholsafe.risk.CombinedRiskTestSupport.TS;
import static kz.zholsafe.risk.CombinedRiskTestSupport.driver;
import static kz.zholsafe.risk.CombinedRiskTestSupport.road;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Required Stage 4.3 fusion matrix tests. Same source timestamps (fresh, zero skew) isolate the
 * deterministic rule matrix; freshness is covered by {@link CombinedRiskFreshnessTest}.
 */
class CombinedRiskEngineTest {

    private final DriverRiskEngine driverEngine = new DriverRiskEngine(DriverGuardConfig.defaults());
    private final CombinedRiskEngine fusion = new CombinedRiskEngine(CombinedRiskConfig.defaults());

    private CombinedRiskSnapshot fuse(RiskLevel roadLevel, RiskLevel driverLevel) {
        return fusion.evaluate(road(TS, roadLevel), driver(driverEngine, TS, driverLevel));
    }

    @Test
    void normalRoadNormalDriverIsNormal() {
        CombinedRiskSnapshot combined = fuse(RiskLevel.NORMAL, RiskLevel.NORMAL);
        assertEquals(CombinedRiskSnapshot.Status.READY, combined.status());
        assertTrue(combined.fused());
        assertEquals(RiskLevel.NORMAL, combined.combinedLevel().orElseThrow());
    }

    @Test
    void roadWarningNormalDriverStaysWarning() {
        CombinedRiskSnapshot combined = fuse(RiskLevel.WARNING, RiskLevel.NORMAL);
        assertEquals(RiskLevel.WARNING, combined.combinedLevel().orElseThrow());
        assertTrue(combined.reasons().contains(CombinedRiskReason.ROAD_HAZARD_PRESENT));
        assertFalse(combined.reasons().contains(CombinedRiskReason.COMBINED_HAZARD_ESCALATION));
    }

    @Test
    void normalRoadDriverWarningStaysWarning() {
        CombinedRiskSnapshot combined = fuse(RiskLevel.NORMAL, RiskLevel.WARNING);
        assertEquals(RiskLevel.WARNING, combined.combinedLevel().orElseThrow());
        assertTrue(combined.reasons().contains(CombinedRiskReason.DRIVER_RISK_PRESENT));
        assertFalse(combined.reasons().contains(CombinedRiskReason.COMBINED_HAZARD_ESCALATION));
    }

    @Test
    void warningPlusWarningEscalatesToCriticalWithExplicitReason() {
        CombinedRiskSnapshot combined = fuse(RiskLevel.WARNING, RiskLevel.WARNING);
        assertEquals(RiskLevel.CRITICAL, combined.combinedLevel().orElseThrow());
        assertTrue(combined.reasons().contains(CombinedRiskReason.COMBINED_HAZARD_ESCALATION),
                "escalation above max(road, driver) must be explicitly explained");
        // Component information is preserved, not erased.
        assertEquals(RiskLevel.WARNING, combined.roadLevel().orElseThrow());
        assertEquals(RiskLevel.WARNING, combined.driverLevel().orElseThrow());
        assertEquals(RiskLevel.WARNING, combined.roadRisk().highestLevel().orElseThrow());
    }

    @Test
    void roadCriticalAloneStaysCriticalWithoutFakeEscalationReason() {
        CombinedRiskSnapshot combined = fuse(RiskLevel.CRITICAL, RiskLevel.NORMAL);
        assertEquals(RiskLevel.CRITICAL, combined.combinedLevel().orElseThrow());
        assertFalse(combined.reasons().contains(CombinedRiskReason.COMBINED_HAZARD_ESCALATION),
                "no escalation happened — the level equals the road component");
    }

    @Test
    void driverCriticalAloneStaysCritical() {
        CombinedRiskSnapshot combined = fuse(RiskLevel.NORMAL, RiskLevel.CRITICAL);
        assertEquals(RiskLevel.CRITICAL, combined.combinedLevel().orElseThrow());
        assertTrue(combined.reasons().contains(CombinedRiskReason.DRIVER_RISK_PRESENT));
    }

    @Test
    void cautionPlusCautionIsNotCritical() {
        CombinedRiskSnapshot combined = fuse(RiskLevel.CAUTION, RiskLevel.CAUTION);
        assertEquals(RiskLevel.CAUTION, combined.combinedLevel().orElseThrow(),
                "CAUTION + CAUTION must stay CAUTION, never CRITICAL");
        assertTrue(combined.reasons().contains(CombinedRiskReason.ROAD_HAZARD_PRESENT));
        assertTrue(combined.reasons().contains(CombinedRiskReason.DRIVER_RISK_PRESENT));
    }

    @Test
    void warningPlusCautionIsNotSilentlyCritical() {
        CombinedRiskSnapshot a = fuse(RiskLevel.WARNING, RiskLevel.CAUTION);
        CombinedRiskSnapshot b = fuse(RiskLevel.CAUTION, RiskLevel.WARNING);
        assertEquals(RiskLevel.WARNING, a.combinedLevel().orElseThrow());
        assertEquals(RiskLevel.WARNING, b.combinedLevel().orElseThrow());
        assertFalse(a.reasons().contains(CombinedRiskReason.COMBINED_HAZARD_ESCALATION));
        assertFalse(b.reasons().contains(CombinedRiskReason.COMBINED_HAZARD_ESCALATION));
        assertTrue(b.reasons().contains(CombinedRiskReason.DRIVER_IMPAIRMENT_WITH_ROAD_HAZARD),
                "driver impairment co-occurring with a road hazard stays visible");
    }

    @Test
    void fusionIsDeterministicAndSymmetric() {
        assertEquals(fuse(RiskLevel.WARNING, RiskLevel.CAUTION), fuse(RiskLevel.WARNING, RiskLevel.CAUTION));
        assertEquals(CombinedRiskEngine.combine(RiskLevel.CAUTION, RiskLevel.WARNING),
                CombinedRiskEngine.combine(RiskLevel.WARNING, RiskLevel.CAUTION));
    }
}
