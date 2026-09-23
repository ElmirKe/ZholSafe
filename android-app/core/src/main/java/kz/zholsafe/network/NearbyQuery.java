package kz.zholsafe.network;

import kz.zholsafe.model.Contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record NearbyQuery(double latitude, double longitude, double radiusMeters, Instant since,
                          NetworkSeverity minimumSeverity, Set<NetworkHazardType> eventTypes,
                          Integer limit) {
    public NearbyQuery {
        Contracts.range("latitude", latitude, -90d, 90d);
        Contracts.range("longitude", longitude, -180d, 180d);
        if (!Double.isFinite(radiusMeters) || radiusMeters <= 0) throw new IllegalArgumentException("invalid radius");
        eventTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
        if (limit != null && limit <= 0) throw new IllegalArgumentException("limit must be positive");
    }
}
