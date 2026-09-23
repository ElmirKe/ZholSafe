package kz.zholsafe.server.hazard;

/**
 * Server-side mirror of the vehicle's {@code kz.zholsafe.model.ObjectClass} names.
 *
 * <p>Kept as a separate enum on purpose (no shared build between app and server). The JSON wire
 * format uses the upper-case enum name (e.g. {@code "HORSE"}).
 *
 * <p>{@link #fromWire(String)} is a LENIENT reader for already-validated / persisted data. It is
 * NOT a validation step: incoming requests must pass {@link HazardEventValidator}, which rejects
 * any value outside the v1 enum (see its forward-compatibility policy).
 */
public enum HazardType {
    PERSON, DOG, HORSE, COW, SHEEP, GOAT, CAMEL, UNKNOWN;

    public static HazardType fromWire(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        try {
            return HazardType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
