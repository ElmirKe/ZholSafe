package kz.zholsafe.network;

import java.time.Instant;

/** Compact Stage 5 public response; intentionally excludes anonymous source identifiers. */
public record NearbyHazard(String eventId, int schemaVersion, NetworkHazardType hazardType,
        NetworkSeverity severity, double latitude, double longitude, Instant sourceTimestamp,
        Instant receivedTimestamp, Instant expiresAt, float confidence, Float headingDegrees,
        Float approximateDistanceMeters, Float ttcSeconds, int reportCount, boolean deduplicated) {}
