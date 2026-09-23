package kz.zholsafe.server.hazard;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface HazardEventSpatialRepository {
    List<HazardEventEntity> findNearby(double latitude, double longitude, double radiusMeters,
                                       Instant now, Instant since, HazardSeverity minimumSeverity,
                                       Set<HazardType> eventTypes, int limit);

    Optional<HazardEventEntity> findConservativeDuplicate(
            String anonymousSourceId, HazardType hazardType, double latitude, double longitude,
            double radiusMeters, Instant receivedAfter, Instant now);
}
