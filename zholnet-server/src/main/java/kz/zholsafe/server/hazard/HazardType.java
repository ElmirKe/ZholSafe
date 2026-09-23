package kz.zholsafe.server.hazard;

/**
 * Server-side mirror of the vehicle's {@code kz.zholsafe.model.ObjectClass} names.
 *
 * <p>Kept as a separate enum on purpose: the server must tolerate unknown values from newer
 * clients (mapped to {@link #UNKNOWN}) instead of rejecting the whole event. The JSON wire
 * format uses the upper-case enum name (e.g. {@code "HORSE"}).
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
