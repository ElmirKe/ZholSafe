package kz.zholsafe.server.hazard;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HazardEventValidatorTest {

    private final HazardEventValidator validator = new HazardEventValidator();

    private static HazardEventDto valid() {
        return new HazardEventDto("e-1", "DEMO-01", "HORSE", 0.94f, 0.91f, 43.24, 76.91,
                Instant.parse("2026-01-01T00:00:00Z"), "ACTIVE", null);
    }

    @Test
    void acceptsContractExample() {
        assertTrue(validator.validate(valid()).valid());
    }

    @Test
    void rejectsOutOfRangeValues() {
        HazardEventDto bad = new HazardEventDto("e-1", "DEMO-01", "HORSE", 1.4f, -0.1f, 95.0, 200.0,
                null, "ACTIVE", null);
        HazardEventValidator.Result r = validator.validate(bad);
        assertFalse(r.valid());
        assertEquals(5, r.errors().size(), r.errors().toString());
    }

    @Test
    void rejectsNullBody() {
        assertFalse(validator.validate(null).valid());
    }

    @Test
    void unknownHazardTypeMapsToUnknownNotError() {
        assertEquals(HazardType.UNKNOWN, HazardType.fromWire("tractor"));
        assertEquals(HazardType.CAMEL, HazardType.fromWire("camel"));
    }
}
