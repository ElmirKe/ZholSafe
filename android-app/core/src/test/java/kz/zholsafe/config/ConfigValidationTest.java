package kz.zholsafe.config;

import kz.zholsafe.model.ObjectClass;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigValidationTest {

    @Test
    void allDefaultsConstruct() {
        assertDoesNotThrow(() -> ZholSafeConfig.defaults(ZholSafeConfig.OperatingMode.LIVE));
    }

    @Test
    void detectorConfigRejectsBadValues() {
        DetectorConfig d = DetectorConfig.defaults();
        assertThrows(IllegalArgumentException.class, () -> new DetectorConfig(d.roadModel(), d.driverModel(), 1.5f, 0.5f, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new DetectorConfig(d.roadModel(), d.driverModel(), 0.5f, Float.NaN, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new DetectorConfig(d.roadModel(), d.driverModel(), 0.5f, 0.5f, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DetectorConfig(d.roadModel(), d.driverModel(), 0.5f, 0.5f, 10, 0));
    }

    @Test
    void trackingConfigRequiresValidCorridor() {
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(0.3f, 10, 2, 30, 0.7f, 0.3f, 0.4f));
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(0.3f, 10, 2, 30, 0.3f, 1.2f, 0.4f));
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(0.3f, 0, 2, 30, 0.3f, 0.7f, 0.4f));
        assertThrows(IllegalArgumentException.class, () -> new TrackingConfig(0.3f, 10, 2, 0, 0.3f, 0.7f, 0.4f));
    }

    @Test
    void driverGuardConfigRequiresPositiveWindows() {
        assertThrows(IllegalArgumentException.class, () -> new DriverGuardConfig(0L, 60_000L, 0.3f, 10_000L, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> new DriverGuardConfig(1500L, -1L, 0.3f, 10_000L, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> new DriverGuardConfig(1500L, 60_000L, 1.3f, 10_000L, 0.5f));
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
