package kz.zholsafe.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataContractTest {

    @Test
    void objectClassLabelsRoundTrip() {
        for (ObjectClass c : ObjectClass.values()) {
            assertEquals(Optional.of(c), ObjectClass.fromLabel(c.label()));
            assertEquals(Optional.of(c), ObjectClass.fromLabel(c.name()));
        }
        assertTrue(ObjectClass.fromLabel("unicorn").isEmpty());
        assertTrue(ObjectClass.fromLabel(null).isEmpty());
    }

    @Test
    void requiredHazardClassesExist() {
        for (String label : new String[] {"horse", "cow", "sheep", "goat", "camel", "dog", "person"}) {
            assertTrue(ObjectClass.fromLabel(label).isPresent(), label);
        }
    }

    @Test
    void detectionValidatesConfidence() {
        BoundingBox b = new BoundingBox(0, 0, 10, 10);
        assertThrows(IllegalArgumentException.class, () -> new Detection(0, ObjectClass.DOG, 1.5f, b, 0L));
        assertThrows(IllegalArgumentException.class, () -> new Detection(0, ObjectClass.DOG, -0.1f, b, 0L));
        assertEquals("dog", new Detection(0, ObjectClass.DOG, 0.5f, b, 0L).className());
    }

    @Test
    void boundingBoxGeometry() {
        BoundingBox a = new BoundingBox(0, 0, 10, 10);
        BoundingBox b = new BoundingBox(5, 5, 15, 15);
        assertEquals(100f, a.area(), 1e-6);
        assertEquals(new Point2D(5f, 5f), a.center());
        assertEquals(25f / 175f, a.iou(b), 1e-5);
        assertEquals(0f, a.iou(new BoundingBox(20, 20, 30, 30)), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> new BoundingBox(10, 0, 0, 10));
    }

    @Test
    void estimateNeverImpliesFalsePrecision() {
        Estimate none = Estimate.unavailable();
        assertFalse(none.available());
        assertEquals(EstimationMethod.NOT_AVAILABLE, none.method());
        assertEquals(-1d, none.orElse(-1d));
        Estimate some = Estimate.of(12.5, EstimationMethod.MONOCULAR_UNCALIBRATED);
        assertTrue(some.available());
        assertThrows(IllegalArgumentException.class, () -> Estimate.of(1d, EstimationMethod.NOT_AVAILABLE));
        assertThrows(IllegalArgumentException.class, () -> new Estimate(true, Double.NaN, EstimationMethod.RADAR));
    }

    @Test
    void hazardEventValidatesRanges() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        HazardEvent ok = new HazardEvent("e1", "DEMO-01", ObjectClass.HORSE, 0.94f, 0.91f, 43.24, 76.91,
                now, HazardStatus.ACTIVE, Optional.empty());
        assertEquals(HazardStatus.ACTIVE, ok.status());
        assertThrows(IllegalArgumentException.class, () -> new HazardEvent("e1", "V", ObjectClass.HORSE, 0.9f, 0.9f,
                91.0, 0.0, now, HazardStatus.ACTIVE, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new HazardEvent("", "V", ObjectClass.HORSE, 0.9f, 0.9f,
                0.0, 0.0, now, HazardStatus.ACTIVE, Optional.empty()));
    }
}
