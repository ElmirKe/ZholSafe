package kz.zholsafe.server.hazard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HazardEventValidatorTest {

    private final HazardEventValidator validator = new HazardEventValidator();
    private static final Instant TS = Instant.parse("2026-01-01T00:00:00Z");

    private static HazardEventDto valid() {
        return new HazardEventDto("e-1", "DEMO-01", "HORSE", 0.94f, 0.91f, 43.24, 76.91, TS, "ACTIVE", null);
    }

    private static HazardEventDto withType(String type) {
        return new HazardEventDto("e-1", "DEMO-01", type, 0.9f, 0.9f, 43.24, 76.91, TS, "ACTIVE", null);
    }

    private static HazardEventDto withStatus(String status) {
        return new HazardEventDto("e-1", "DEMO-01", "HORSE", 0.9f, 0.9f, 43.24, 76.91, TS, status, null);
    }

    private static HazardEventDto numbers(Float conf, Float risk, Double lat, Double lon) {
        return new HazardEventDto("e-1", "DEMO-01", "HORSE", conf, risk, lat, lon, TS, "ACTIVE", null);
    }

    @Test
    void acceptsContractExample() {
        assertTrue(validator.validate(valid()).valid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PERSON", "DOG", "HORSE", "COW", "SHEEP", "GOAT", "CAMEL",
            "STOPPED_VEHICLE", "OBSTACLE", "OTHER", "UNKNOWN"})
    void acceptsEveryV1HazardTypeIncludingCanonicalUnknown(String type) {
        assertTrue(validator.validate(withType(type)).valid(), type);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tractor", "horse", "Horse", " HORSE", "HORSE ", "DRAGON", "", "  "})
    void rejectsNonV1HazardTypesInsteadOfCoercingToUnknown(String type) {
        HazardEventValidator.Result r = validator.validate(withType(type));
        assertFalse(r.valid(), "should reject '" + type + "'");
        assertTrue(r.errors().stream().anyMatch(e -> e.startsWith("hazardType")), r.errors().toString());
    }

    @Test
    void rejectsNullHazardType() {
        assertFalse(validator.validate(withType(null)).valid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "EXPIRED", "CONFIRMED", "DISMISSED"})
    void acceptsEveryV1Status(String status) {
        assertTrue(validator.validate(withStatus(status)).valid(), status);
    }

    @Test
    void statusIsOptionalAndDefaultsToActiveByContract() {
        assertTrue(validator.validate(withStatus(null)).valid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "Active", "PENDING", "", "ACTIVE "})
    void rejectsNonV1Status(String status) {
        HazardEventValidator.Result r = validator.validate(withStatus(status));
        assertFalse(r.valid(), "should reject '" + status + "'");
        assertTrue(r.errors().stream().anyMatch(e -> e.startsWith("status")), r.errors().toString());
    }

    @Test
    void rejectsOutOfRangeValues() {
        HazardEventValidator.Result r = validator.validate(numbers(1.4f, -0.1f, 95.0, 200.0));
        assertFalse(r.valid());
        assertEquals(4, r.errors().size(), r.errors().toString());
    }

    @Test
    void rejectsNaNAndInfinity() {
        assertFalse(validator.validate(numbers(Float.NaN, 0.5f, 43.0, 76.0)).valid());
        assertFalse(validator.validate(numbers(0.5f, Float.POSITIVE_INFINITY, 43.0, 76.0)).valid());
        assertFalse(validator.validate(numbers(0.5f, 0.5f, Double.NaN, 76.0)).valid());
        assertFalse(validator.validate(numbers(0.5f, 0.5f, 43.0, Double.NEGATIVE_INFINITY)).valid());
    }

    @Test
    void rejectsMissingNumbersAndTimestamp() {
        assertFalse(validator.validate(numbers(null, 0.5f, 43.0, 76.0)).valid());
        assertFalse(validator.validate(new HazardEventDto("e", "v", "HORSE", 0.5f, 0.5f, 43.0, 76.0, null, "ACTIVE", null)).valid());
    }

    @Test
    void rejectsNullBody() {
        assertFalse(validator.validate(null).valid());
    }

    @Test
    void rejectsNonFiniteOptionalDiagnosticsAndEvidenceReference() {
        HazardEventDto invalid = new HazardEventDto("e", "anon", "HORSE", 0.8f, 0.7f,
                43d, 76d, TS, "ACTIVE", "image://not-accepted", 1, "WARNING",
                Float.POSITIVE_INFINITY, 0f, Float.NaN);
        HazardEventValidator.Result result = validator.validate(invalid);
        assertFalse(result.valid());
        assertEquals(4, result.errors().size(), result.errors().toString());
    }

    @Test
    void fromWireIsLenientReaderNotValidator() {
        assertEquals(HazardType.UNKNOWN, HazardType.fromWire("tractor"));
        assertEquals(HazardType.CAMEL, HazardType.fromWire("camel"));
    }
}
