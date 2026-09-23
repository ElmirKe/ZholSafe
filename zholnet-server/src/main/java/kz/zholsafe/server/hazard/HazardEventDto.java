package kz.zholsafe.server.hazard;

import java.time.Instant;

/**
 * Wire DTO for hazard events (contract version 1). Mirrors the vehicle-side
 * {@code kz.zholsafe.model.HazardEvent} and {@code tests/contracts/hazard-event.v1.schema.json}.
 *
 * <p>Everything in here is UNTRUSTED client input: {@code vehicleId} is self-declared and
 * {@code confidence}/{@code risk} are self-reported. Validation rules live in
 * {@link HazardEventValidator}; authentication and device identity are future extension points
 * (see {@code kz.zholsafe.server.config}).
 *
 * @param evidenceReference optional; NOT used by the MVP, reserved for future evidence snapshots
 */
public record HazardEventDto(
        String eventId,
        String vehicleId,
        String hazardType,
        Float confidence,
        Float risk,
        Double latitude,
        Double longitude,
        Instant timestamp,
        String status,
        String evidenceReference) {
}
