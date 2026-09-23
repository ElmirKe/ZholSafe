package kz.zholsafe.server.hazard;

import java.time.Instant;

/** Compact public event metadata. Source tokens and persistence identifiers are never returned. */
public record HazardEventResponse(
        String eventId,
        int schemaVersion,
        HazardType hazardType,
        HazardSeverity severity,
        double latitude,
        double longitude,
        Instant sourceTimestamp,
        Instant receivedTimestamp,
        Instant expiresAt,
        float confidence,
        Float headingDegrees,
        Float approximateDistanceMeters,
        Float ttcSeconds,
        int reportCount,
        boolean deduplicated) {

    public static HazardEventResponse from(HazardEventEntity event, boolean deduplicated) {
        return new HazardEventResponse(event.eventId(), event.schemaVersion(), event.hazardType(),
                event.severity(), event.location().getY(), event.location().getX(),
                event.sourceTimestamp(), event.receivedAt(), event.expiresAt(), event.confidence(),
                event.headingDegrees(), event.approximateDistanceMeters(), event.ttcSeconds(),
                event.reportCount(), deduplicated);
    }
}
