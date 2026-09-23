package kz.zholsafe.server.hazard;

/**
 * Server network vocabulary. It includes the vehicle's current canonical object classes plus
 * broader future/manual/other-sensor hazard categories; this does not claim detector support.
 *
 * <p>Kept as a separate enum on purpose (no shared build between app and server). The JSON wire
 * format uses the upper-case enum name (e.g. {@code "HORSE"}).
 *
 * <p>{@link #fromWire(String)} is a LENIENT reader for already-validated / persisted data. It is
 * NOT a validation step: incoming requests must pass {@link HazardEventValidator}, which rejects
 * any value outside the Stage 5 v1 network vocabulary.
 */
public enum HazardType {
    PERSON, DOG, HORSE, COW, SHEEP, GOAT, CAMEL,
    STOPPED_VEHICLE, OBSTACLE, OTHER, UNKNOWN;

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
