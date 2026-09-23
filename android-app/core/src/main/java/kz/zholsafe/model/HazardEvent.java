package kz.zholsafe.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * DATA CONTRACT: compact structured hazard event shared with ZholNet.
 *
 * <p>This is the ONLY thing normally uploaded to the server: no raw video, no frames.
 * Wire format (JSON, version 1) is defined in {@code tests/contracts/hazard-event.v1.schema.json}
 * and documented in {@code docs/DATA_CONTRACTS.md}. Keep both in sync.
 *
 * @param eventId           client-generated UUID string; the server may de-duplicate on it
 * @param vehicleId         self-declared vehicle identifier — NOT trusted by the server
 * @param hazardType        canonical class of the hazard
 * @param confidence        detector confidence in [0,1]
 * @param risk              local Risk Engine total risk in [0,1] at the time of reporting
 * @param latitude          WGS84 degrees
 * @param longitude         WGS84 degrees
 * @param timestamp         wall-clock time of the detection (UTC)
 * @param status            lifecycle status (client always submits {@link HazardStatus#ACTIVE})
 * @param evidenceReference optional opaque reference to an evidence snapshot (NOT used in MVP)
 */
public record HazardEvent(
        String eventId,
        String vehicleId,
        ObjectClass hazardType,
        float confidence,
        float risk,
        double latitude,
        double longitude,
        Instant timestamp,
        HazardStatus status,
        Optional<String> evidenceReference) {

    /** Wire-format version this model corresponds to. */
    public static final int CONTRACT_VERSION = 1;

    public HazardEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(hazardType, "hazardType");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evidenceReference, "evidenceReference");
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        Contracts.unit("confidence", confidence);
        Contracts.unit("risk", risk);
        Contracts.range("latitude", latitude, -90d, 90d);
        Contracts.range("longitude", longitude, -180d, 180d);
    }
}
