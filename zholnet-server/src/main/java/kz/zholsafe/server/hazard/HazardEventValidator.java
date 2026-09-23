package kz.zholsafe.server.hazard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure validation of incoming hazard events against contract v1
 * ({@code tests/contracts/hazard-event.v1.schema.json}). No Spring dependency.
 *
 * <h2>Forward-compatibility policy (decided in Stage 0.1)</h2>
 * <ul>
 *   <li>{@code hazardType} must be one of the v1 enum names, <b>including</b> the canonical
 *       {@code UNKNOWN} (a client may legitimately report a hazard whose class it could not map).
 *       Any other string is <b>rejected</b> (HTTP 400 in Stage 5). Malformed input is not
 *       silently coerced to {@code UNKNOWN}: {@link HazardType#fromWire(String)} is a lenient
 *       helper for reading already-persisted data, not a substitute for validation.</li>
 *   <li>Adding a new class in a future contract version means bumping the contract version;
 *       a v1 server rejects it, which is the intended signal that the client is newer.</li>
 *   <li>{@code status} is optional (defaults to {@code ACTIVE}); if present it must be a v1
 *       status name. Only {@code ACTIVE} is meaningful from a vehicle, but the other values are
 *       syntactically accepted so operator/back-office tools can share the DTO.</li>
 *   <li>Matching is case-sensitive upper-case, as in the JSON schema.</li>
 *   <li>All numeric fields must be finite (NaN/Infinity rejected) and within range.</li>
 * </ul>
 * Stage 5 extends this with rate limiting and plausibility checks.
 */
public final class HazardEventValidator {

    public record Result(boolean valid, List<String> errors) {
        public static Result ok() {
            return new Result(true, List.of());
        }
    }

    public Result validate(HazardEventDto dto) {
        List<String> errors = new ArrayList<>();
        if (dto == null) {
            return new Result(false, List.of("body is required"));
        }
        if (isBlank(dto.eventId())) errors.add("eventId is required");
        if (isBlank(dto.vehicleId())) errors.add("vehicleId is required");

        if (isBlank(dto.hazardType())) {
            errors.add("hazardType is required");
        } else if (!isEnumName(HazardType.class, dto.hazardType())) {
            errors.add("hazardType must be one of " + names(HazardType.class));
        }

        if (dto.status() != null && !isEnumName(HazardStatus.class, dto.status())) {
            errors.add("status must be one of " + names(HazardStatus.class));
        }

        checkUnit(errors, "confidence", dto.confidence());
        checkUnit(errors, "risk", dto.risk());
        checkRange(errors, "latitude", dto.latitude(), -90d, 90d);
        checkRange(errors, "longitude", dto.longitude(), -180d, 180d);
        if (dto.timestamp() == null) errors.add("timestamp is required");

        return errors.isEmpty() ? Result.ok() : new Result(false, List.copyOf(errors));
    }

    private static void checkUnit(List<String> errors, String name, Float v) {
        if (v == null || v.isNaN() || v.isInfinite() || v < 0f || v > 1f) {
            errors.add(name + " must be a finite number in [0,1]");
        }
    }

    private static void checkRange(List<String> errors, String name, Double v, double min, double max) {
        if (v == null || v.isNaN() || v.isInfinite() || v < min || v > max) {
            errors.add(name + " must be a finite number in [" + min + "," + max + "]");
        }
    }

    /** Exact (case-sensitive) enum-name match, as required by the JSON schema. */
    private static <E extends Enum<E>> boolean isEnumName(Class<E> type, String value) {
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static <E extends Enum<E>> String names(Class<E> type) {
        List<String> n = new ArrayList<>();
        for (E e : type.getEnumConstants()) n.add(e.name());
        return String.join("|", n).toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
