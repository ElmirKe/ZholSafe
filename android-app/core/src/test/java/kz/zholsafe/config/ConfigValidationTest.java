package kz.zholsafe.config;

import kz.zholsafe.model.ObjectClass;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigValidationTest {

    @Test
    void allDefaultsConstruct() {
        assertDoesNotThrow(() -> ZholSafeConfig.defaults(ZholSafeConfig.OperatingMode.LIVE));
    }

    @Test
    void detectorConfigRejectsBadValues() {
        DetectorConfig d = DetectorConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> new DetectorConfig(d.roadModelDir(), d.executionProvider(), d.roadModel(), d.driverModel(), 1.5f, 0.5f, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new DetectorConfig(d.roadModelDir(), d.executionProvider(), d.roadModel(), d.driverModel(), 0.5f, Float.NaN, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new DetectorConfig(d.roadModelDir(), d.executionProvider(), d.roadModel(), d.driverModel(), 0.5f, 0.5f, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DetectorConfig(d.roadModelDir(), d.executionProvider(), d.roadModel(), d.driverModel(), 0.5f, 0.5f, 10, 0));
    }

    @Test
    void trackingConfigRequiresValidCorridor() {
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(0.3f, 10, 2, 30, 0.7f, 0.3f, 0.4f));
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(0.3f, 10, 2, 30, 0.3f, 1.2f, 0.4f));
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(0.3f, 0, 2, 30, 0.3f, 0.7f, 0.4f));
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(0.3f, 10, 2, 0, 0.3f, 0.7f, 0.4f));
    }

    @Test
    void trackingConfigValidatesAllNewThresholdsAndBounds() {
        TrackingConfig c = TrackingConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(0, 1, 1, 1, .3f, .7f, .4f, .6f, .3f, 10));
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(.3f, 1, 0, 1, .3f, .7f, .4f, .6f, .3f, 10));
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(.3f, 1, 1, 1, .3f, .7f, .4f, Float.NaN, .3f, 10));
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(.3f, 1, 1, 1, .3f, .7f, .4f, .6f, .6f, 10));
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(.3f, 1, 1, 1, .3f, .7f, .4f, .6f, -1f, 10));
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(.3f, 1, 1, 1, .3f, .7f, .4f, .6f, .3f, 0));
        assertDoesNotThrow(() -> new TrackingConfig(c.iouMatchThreshold(), c.maxCoastFrames(), c.minHitsToConfirm(),
                c.historyLength(), c.corridorLeftFraction(), c.corridorRightFraction(), c.corridorTopFraction()));
    }

    @Test
    void driverGuardConfigValidatesStage43Thresholds() {
        DriverGuardConfig d = DriverGuardConfig.defaults();
        // eye-closed must be strictly below partially-closed
        assertThrows(IllegalArgumentException.class, () -> new DriverGuardConfig(
                0.70f, 0.60f, 0.5f, 0.7d, 1.5d, 3.0d, 60d, 0.5f, 0.15f, 0.30f, 0.6f, 2.0d,
                30f, 25f, 2.0d, 5.0d, 5.0d, 1.0d, 4096));
        // closure thresholds must be strictly ordered caution < warning < critical
        assertThrows(IllegalArgumentException.class, () -> new DriverGuardConfig(
                0.30f, 0.60f, 0.5f, 1.5d, 1.5d, 3.0d, 60d, 0.5f, 0.15f, 0.30f, 0.6f, 2.0d,
                30f, 25f, 2.0d, 5.0d, 5.0d, 1.0d, 4096));
        // PERCLOS window must be positive
        assertThrows(IllegalArgumentException.class, () -> new DriverGuardConfig(
                0.30f, 0.60f, 0.5f, 0.7d, 1.5d, 3.0d, -1d, 0.5f, 0.15f, 0.30f, 0.6f, 2.0d,
                30f, 25f, 2.0d, 5.0d, 5.0d, 1.0d, 4096));
        // confidence must be a unit value
        assertThrows(IllegalArgumentException.class, () -> new DriverGuardConfig(
                0.30f, 0.60f, 1.2f, 0.7d, 1.5d, 3.0d, 60d, 0.5f, 0.15f, 0.30f, 0.6f, 2.0d,
                30f, 25f, 2.0d, 5.0d, 5.0d, 1.0d, 4096));
        // PERCLOS caution must not exceed warning
        assertThrows(IllegalArgumentException.class, () -> new DriverGuardConfig(
                0.30f, 0.60f, 0.5f, 0.7d, 1.5d, 3.0d, 60d, 0.5f, 0.45f, 0.30f, 0.6f, 2.0d,
                30f, 25f, 2.0d, 5.0d, 5.0d, 1.0d, 4096));
        // NaN durations rejected
        assertThrows(IllegalArgumentException.class, () -> new DriverGuardConfig(
                0.30f, 0.60f, 0.5f, 0.7d, Double.NaN, 3.0d, 60d, 0.5f, 0.15f, 0.30f, 0.6f, 2.0d,
                30f, 25f, 2.0d, 5.0d, 5.0d, 1.0d, 4096));
        // bounded-memory hard cap must allow at least a pair of observations
        assertThrows(IllegalArgumentException.class, () -> new DriverGuardConfig(
                0.30f, 0.60f, 0.5f, 0.7d, 1.5d, 3.0d, 60d, 0.5f, 0.15f, 0.30f, 0.6f, 2.0d,
                30f, 25f, 2.0d, 5.0d, 5.0d, 1.0d, 1));
        assertDoesNotThrow(DriverGuardConfig::defaults);
    }

    @Test
    void driverGuardLegacyShimsStayConsistent() {
        DriverGuardConfig d = DriverGuardConfig.defaults();
        assertEquals(1500L, d.prolongedEyeClosureMillis(), "1.5 s warning threshold in ms");
        assertEquals(0.30f, d.perclosWarningFraction(), 1e-6f);
    }

    @Test
    void combinedRiskConfigRequiresPositiveBudgets() {
        assertThrows(IllegalArgumentException.class, () -> new CombinedRiskConfig(0d, 2d, 1d));
        assertThrows(IllegalArgumentException.class, () -> new CombinedRiskConfig(2d, -1d, 1d));
        assertThrows(IllegalArgumentException.class, () -> new CombinedRiskConfig(2d, 2d, Double.NaN));
        assertDoesNotThrow(CombinedRiskConfig::defaults);
    }

    @Test
    void networkConfigRequiresPositiveQueue() {
        assertThrows(IllegalArgumentException.class, () -> new NetworkConfig("http://x", "ws://x", 0, 1000L, 5000));
        assertThrows(IllegalArgumentException.class, () -> new NetworkConfig("http://x", "ws://x", 10, -1L, 5000));
    }

    @Test
    void riskConfigRejectsOutOfRangeWeightsAndThresholds() {
        RiskConfig r = RiskConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> new RiskConfig.LevelThresholds(0.8f, 0.5f, 0.2f));
        assertThrows(IllegalArgumentException.class, () -> new RiskConfig.LevelThresholds(0.2f, 0.5f, 1.2f));
        assertThrows(IllegalArgumentException.class, () -> new RiskConfig.DriverWeights(1.5f, 0.5f, 0.9f, 0.7f, 0.3f));
        assertThrows(IllegalArgumentException.class, () -> new RiskConfig.TotalWeights(0.3f, 0.4f, Float.NaN, 0.4f, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> new RiskConfig.CollisionWeights(0.6f, 0.7f, -1d, 0.9f, 15d, 0.8f));
        assertThrows(IllegalArgumentException.class, () -> new RiskConfig.RoadWeights(1f, 0.3f, 0.2f, 0.8f, 0, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> new RiskConfig(Map.of(ObjectClass.HORSE, 1.5f), r.driverWeights(),
                r.roadWeights(), r.collisionWeights(), r.totalWeights(), r.levelThresholds(), r.driverGuard(), 0.35f, 22f));
        assertThrows(IllegalArgumentException.class, () -> new RiskConfig(r.classWeights(), r.driverWeights(),
                r.roadWeights(), r.collisionWeights(), r.totalWeights(), r.levelThresholds(), r.driverGuard(), 0.35f, Float.NaN));
    }
}
