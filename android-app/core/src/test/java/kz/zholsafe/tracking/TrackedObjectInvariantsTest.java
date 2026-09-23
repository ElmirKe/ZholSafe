package kz.zholsafe.tracking;

import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Estimate;
import kz.zholsafe.model.EstimationMethod;
import kz.zholsafe.model.ObjectClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackedObjectInvariantsTest {

    private static final BoundingBox BOX = new BoundingBox(0, 0, 10, 10);

    private static TrackedObject make(float conf, int age, Estimate dist, Estimate ttc) {
        return new TrackedObject(1, ObjectClass.COW, conf, BOX, List.of(BOX.center()), MovementClass.UNKNOWN,
                dist, ttc, false, age, 1L);
    }

    @Test
    void confidenceMustBeFiniteUnit() {
        assertThrows(IllegalArgumentException.class, () -> make(Float.NaN, 1, Estimate.unavailable(), Estimate.unavailable()));
        assertThrows(IllegalArgumentException.class, () -> make(Float.POSITIVE_INFINITY, 1, Estimate.unavailable(), Estimate.unavailable()));
        assertThrows(IllegalArgumentException.class, () -> make(-0.1f, 1, Estimate.unavailable(), Estimate.unavailable()));
        assertThrows(IllegalArgumentException.class, () -> make(1.1f, 1, Estimate.unavailable(), Estimate.unavailable()));
        assertDoesNotThrow(() -> make(0.5f, 0, Estimate.unavailable(), Estimate.unavailable()));
    }

    @Test
    void ageFramesMustBeNonNegative() {
        assertThrows(IllegalArgumentException.class, () -> make(0.5f, -1, Estimate.unavailable(), Estimate.unavailable()));
    }

    @Test
    void negativeDistanceOrTtcEstimatesAreRejectedEvenViaGenericFactory() {
        assertThrows(IllegalArgumentException.class,
                () -> make(0.5f, 1, Estimate.of(-1d, EstimationMethod.MONOCULAR_UNCALIBRATED), Estimate.unavailable()));
        assertThrows(IllegalArgumentException.class,
                () -> make(0.5f, 1, Estimate.unavailable(), Estimate.of(-1d, EstimationMethod.SCALE_CHANGE)));
        assertDoesNotThrow(() -> make(0.5f, 1,
                Estimate.distance(8d, EstimationMethod.MONOCULAR_UNCALIBRATED), Estimate.ttc(2d, EstimationMethod.SCALE_CHANGE)));
    }
}
