package kz.zholsafe.driver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverStateInvariantsTest {

    private static DriverState state(long closure, boolean perclosAvail, float perclos, float conf) {
        return new DriverState(true, false, closure, perclosAvail, perclos, HeadPose.UNAVAILABLE, false, conf, 1L);
    }

    @Test
    void eyeClosureDurationMustBeNonNegative() {
        assertThrows(IllegalArgumentException.class, () -> state(-1L, false, Float.NaN, 0.9f));
        assertDoesNotThrow(() -> state(0L, false, Float.NaN, 0.9f));
    }

    @Test
    void confidenceMustBeFiniteUnit() {
        assertThrows(IllegalArgumentException.class, () -> state(0L, false, Float.NaN, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> state(0L, false, Float.NaN, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> state(0L, false, Float.NaN, 1.2f));
    }

    @Test
    void perclosMustBeFiniteUnitWhenAvailable() {
        assertThrows(IllegalArgumentException.class, () -> state(0L, true, Float.NaN, 0.9f));
        assertThrows(IllegalArgumentException.class, () -> state(0L, true, Float.POSITIVE_INFINITY, 0.9f));
        assertThrows(IllegalArgumentException.class, () -> state(0L, true, 1.5f, 0.9f));
        assertDoesNotThrow(() -> state(0L, true, 0.3f, 0.9f));
    }

    @Test
    void perclosMustStayNaNWhenUnavailable() {
        // A numeric PERCLOS with the flag off would be a fabricated measurement.
        assertThrows(IllegalArgumentException.class, () -> state(0L, false, 0.3f, 0.9f));
        DriverState s = state(0L, false, Float.NaN, 0.9f);
        assertFalse(s.perclosAvailable());
        assertTrue(Float.isNaN(s.perclos()));
    }

    @Test
    void unavailableFactoriesStayValid() {
        assertDoesNotThrow(() -> DriverState.unavailable(1L));
        assertDoesNotThrow(() -> DriverObservation.noFace(1L));
    }

    @Test
    void observationAndHeadPoseRejectNonFinite() {
        assertThrows(IllegalArgumentException.class, () -> new DriverObservation(true, true, false, Float.POSITIVE_INFINITY,
                HeadPose.UNAVAILABLE, false, false, 0.9f, 1L));
        assertThrows(IllegalArgumentException.class, () -> new DriverObservation(true, true, false, Float.NaN,
                HeadPose.UNAVAILABLE, false, false, Float.NaN, 1L));
        assertThrows(IllegalArgumentException.class, () -> HeadPose.of(Float.NaN, 0f, 0f));
        assertDoesNotThrow(() -> HeadPose.of(10f, -5f, 0f));
    }
}
