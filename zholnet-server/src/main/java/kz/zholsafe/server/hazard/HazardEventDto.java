package kz.zholsafe.server.hazard;

import java.time.Instant;

/**
 * Wire DTO for hazard events (contract version 1). Mirrors the vehicle-side
 * {@code kz.zholsafe.model.HazardEvent} and {@code tests/contracts/hazard-event.v1.schema.json}.
 *
 * <p>Everything in here is UNTRUSTED client input: {@code vehicleId} is an anonymous ephemeral
 * source token (legacy field name) and
 * {@code confidence}/{@code risk} are self-reported. Validation rules live in
 * {@link HazardEventValidator}; authentication and device identity are future extension points
 * (see {@code kz.zholsafe.server.config}).
 *
 * @param evidenceReference legacy reserved field; non-empty values are rejected in Stage 5
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
        String evidenceReference,
        Integer schemaVersion,
        String severity,
        Float headingDegrees,
        Float approximateDistanceMeters,
        Float ttcSeconds) {

    /** Backward-compatible constructor for the established compact v1 vehicle contract. */
    public HazardEventDto(String eventId, String vehicleId, String hazardType, Float confidence,
                          Float risk, Double latitude, Double longitude, Instant timestamp,
                          String status, String evidenceReference) {
        this(eventId, vehicleId, hazardType, confidence, risk, latitude, longitude, timestamp,
                status, evidenceReference, 1, null, null, null, null);
    }
}
