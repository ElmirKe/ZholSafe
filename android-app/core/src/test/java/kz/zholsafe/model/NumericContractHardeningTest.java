package kz.zholsafe.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 0.1: NaN / infinity rejection across the numeric data contracts. */
class NumericContractHardeningTest {

    private static final BoundingBox BOX = new BoundingBox(0, 0, 10, 10);
    private static final Instant TS = Instant.parse("2026-01-01T00:00:00Z");

    private static HazardEvent event(float conf, float risk, double lat, double lon) {
        return new HazardEvent("e", "V", ObjectClass.HORSE, conf, risk, lat, lon, TS, HazardStatus.ACTIVE, Optional.empty());
    }

    @Test
    void detectionRejectsNaNAndInfiniteConfidence() {
        assertThrows(IllegalArgumentException.class, () -> new Detection(0, ObjectClass.DOG, Float.NaN, BOX, 0L));
        assertThrows(IllegalArgumentException.class, () -> new Detection(0, ObjectClass.DOG, Float.POSITIVE_INFINITY, BOX, 0L));
        assertDoesNotThrow(() -> new Detection(0, ObjectClass.DOG, 0f, BOX, 0L));
        assertDoesNotThrow(() -> new Detection(0, ObjectClass.DOG, 1f, BOX, 0L));
    }

    @Test
    void hazardEventRejectsNonFiniteNumbers() {
        assertThrows(IllegalArgumentException.class, () -> event(Float.NaN, 0.5f, 43, 76));
        assertThrows(IllegalArgumentException.class, () -> event(0.5f, Float.NEGATIVE_INFINITY, 43, 76));
        assertThrows(IllegalArgumentException.class, () -> event(0.5f, 0.5f, Double.NaN, 76));
        assertThrows(IllegalArgumentException.class, () -> event(0.5f, 0.5f, 43, Double.POSITIVE_INFINITY));
        assertDoesNotThrow(() -> event(0.5f, 0.5f, -90, 180));
    }

    @Test
    void geoPositionRequiresFiniteInRangeCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(Double.NaN, 76, Float.NaN, Float.NaN, Float.NaN, 0L));
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(43, Double.POSITIVE_INFINITY, Float.NaN, Float.NaN, Float.NaN, 0L));
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(91, 76, Float.NaN, Float.NaN, Float.NaN, 0L));
    }

    @Test
    void geoPositionOptionalFieldsKeepNaNSentinelButRejectInfinityAndNegatives() {
        GeoPosition none = new GeoPosition(43, 76, Float.NaN, Float.NaN, Float.NaN, 0L);
        assertFalse(none.accuracyAvailable());
        assertFalse(none.speedAvailable());
        assertFalse(none.bearingAvailable());
        GeoPosition full = new GeoPosition(43, 76, 5f, 20f, 90f, 0L);
        assertTrue(full.accuracyAvailable() && full.speedAvailable() && full.bearingAvailable());
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(43, 76, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN, 0L));
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(43, 76, Float.NaN, Float.NEGATIVE_INFINITY, Float.NaN, 0L));
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(43, 76, Float.NaN, Float.NaN, Float.POSITIVE_INFINITY, 0L));
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(43, 76, -1f, Float.NaN, Float.NaN, 0L));
        assertThrows(IllegalArgumentException.class, () -> new GeoPosition(43, 76, Float.NaN, -3f, Float.NaN, 0L));
    }

    @Test
    void estimateRejectsNonFiniteAndInconsistentStates() {
        assertThrows(IllegalArgumentException.class, () -> Estimate.of(Double.NaN, EstimationMethod.RADAR));
        assertThrows(IllegalArgumentException.class, () -> Estimate.of(Double.POSITIVE_INFINITY, EstimationMethod.RADAR));
        assertThrows(IllegalArgumentException.class, () -> new Estimate(false, 3d, EstimationMethod.NOT_AVAILABLE));
        assertThrows(IllegalArgumentException.class, () -> new Estimate(true, 3d, EstimationMethod.NOT_AVAILABLE));
    }
}
