package kz.zholsafe.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VehicleContextTest {

    @Test
    void availableSpeedMustBeFiniteNonNegative() {
        assertThrows(IllegalArgumentException.class, () -> new VehicleContext(true, Float.NaN, false));
        assertThrows(IllegalArgumentException.class, () -> new VehicleContext(true, Float.POSITIVE_INFINITY, false));
        assertThrows(IllegalArgumentException.class, () -> new VehicleContext(true, -1f, false));
        assertDoesNotThrow(() -> VehicleContext.withSpeed(0f));
        assertDoesNotThrow(() -> VehicleContext.withSpeed(25f));
    }

    @Test
    void unavailableSpeedMustBeNaNSentinel() {
        assertThrows(IllegalArgumentException.class, () -> new VehicleContext(false, 12f, false));
        assertFalse(VehicleContext.UNKNOWN.speedAvailable());
    }
}
