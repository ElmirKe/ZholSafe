package kz.zholsafe.server.hazard;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure validation of incoming hazard events. No Spring dependency so it is trivially unit-tested.
 * Stage 5 extends this with rate limiting, plausibility checks (speed between consecutive
 * events of the same vehicle) and geofencing.
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
        if (isBlank(dto.hazardType())) errors.add("hazardType is required");
        if (dto.confidence() == null || dto.confidence() < 0f || dto.confidence() > 1f) {
            errors.add("confidence must be in [0,1]");
        }
        if (dto.risk() == null || dto.risk() < 0f || dto.risk() > 1f) {
            errors.add("risk must be in [0,1]");
        }
        if (dto.latitude() == null || dto.latitude() < -90d || dto.latitude() > 90d) {
            errors.add("latitude must be in [-90,90]");
        }
        if (dto.longitude() == null || dto.longitude() < -180d || dto.longitude() > 180d) {
            errors.add("longitude must be in [-180,180]");
        }
        if (dto.timestamp() == null) errors.add("timestamp is required");
        return errors.isEmpty() ? Result.ok() : new Result(false, List.copyOf(errors));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
