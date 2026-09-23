package kz.zholsafe.location;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

class LocationFixTest {
    @Test void validFreshFixAccepted() {
        LocationFix fix = fix(10_000);
        assertTrue(fix.freshAt(12_000, 2_000));
        assertFalse(fix.freshAt(12_001, 2_000));
    }

    @Test void invalidCoordinatesAndOptionalValuesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LocationFix(91, 0, Instant.EPOCH,
                1, 2, OptionalDouble.empty(), OptionalDouble.empty(), LocationQuality.PRECISE));
        assertThrows(IllegalArgumentException.class, () -> new LocationFix(0, 181, Instant.EPOCH,
                1, 2, OptionalDouble.empty(), OptionalDouble.empty(), LocationQuality.PRECISE));
        assertThrows(IllegalArgumentException.class, () -> new LocationFix(0, 0, Instant.EPOCH,
                1, Double.NaN, OptionalDouble.empty(), OptionalDouble.empty(), LocationQuality.PRECISE));
        assertThrows(IllegalArgumentException.class, () -> new LocationFix(0, 0, Instant.EPOCH,
                1, 2, OptionalDouble.of(360), OptionalDouble.empty(), LocationQuality.PRECISE));
    }

    private static LocationFix fix(long elapsed) {
        return new LocationFix(43.238, 76.945, Instant.parse("2026-09-23T12:00:00Z"), elapsed,
                4, OptionalDouble.of(90), OptionalDouble.of(8), LocationQuality.PRECISE);
    }
}
