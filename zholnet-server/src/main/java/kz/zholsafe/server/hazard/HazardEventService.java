package kz.zholsafe.server.hazard;

import kz.zholsafe.server.config.ZholNetProperties;
import kz.zholsafe.server.websocket.HazardNotificationPublisher;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class HazardEventService {
    private static final GeometryFactory GEOMETRY =
            new GeometryFactory(new PrecisionModel(), 4326);
    private static final float DEDUP_HEADING_TOLERANCE_DEGREES = 10f;
    private static final float DEDUP_DISTANCE_TOLERANCE_METERS = 5f;

    private final HazardEventRepository repository;
    private final HazardEventValidator validator;
    private final HazardNotificationPublisher publisher;
    private final ZholNetProperties properties;
    private final Clock clock;

    public HazardEventService(HazardEventRepository repository,
                              HazardNotificationPublisher publisher,
                              ZholNetProperties properties, Clock clock) {
        this(repository, new HazardEventValidator(), publisher, properties, clock);
    }

    HazardEventService(HazardEventRepository repository, HazardEventValidator validator,
                       HazardNotificationPublisher publisher, ZholNetProperties properties,
                       Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.validator = Objects.requireNonNull(validator);
        this.publisher = Objects.requireNonNull(publisher);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public HazardEventResponse accept(HazardEventDto request) {
        HazardEventValidator.Result validation = validator.validate(request);
        if (!validation.valid()) throw new InvalidHazardRequestException(validation.errors());

        HazardType type = HazardType.valueOf(request.hazardType());
        HazardSeverity severity = request.severity() == null
                ? HazardSeverity.fromRisk(request.risk()) : HazardSeverity.valueOf(request.severity());
        Instant receivedAt = clock.instant();
        Instant expiresAt = receivedAt.plus(properties.defaultTtl());

        var exact = repository.findByEventId(request.eventId());
        if (exact.isPresent()) {
            if (!sameIdentity(exact.get(), request, type)) {
                throw new HazardConflictException("eventId already belongs to a different hazard");
            }
            return HazardEventResponse.from(exact.get(), true);
        }

        var candidate = repository.findConservativeDuplicate(request.vehicleId(), type,
                request.latitude(), request.longitude(), properties.deduplicationRadiusMeters(),
                receivedAt.minus(properties.deduplicationWindow()), receivedAt);
        if (candidate.isPresent() && hasMatchingObservation(candidate.get(), request)) {
            HazardEventEntity existing = candidate.get();
            existing.mergeReport(request.confidence(), request.risk(), severity, request.timestamp(),
                    receivedAt, expiresAt, request.headingDegrees(),
                    request.approximateDistanceMeters(), request.ttcSeconds());
            HazardEventEntity saved = repository.save(existing);
            HazardEventResponse response = HazardEventResponse.from(saved, true);
            publisher.publish(response);
            return response;
        }

        Point point = GEOMETRY.createPoint(new Coordinate(request.longitude(), request.latitude()));
        HazardEventEntity event = new HazardEventEntity(request.eventId(), request.vehicleId(), type,
                severity, request.confidence(), request.risk(), point, request.timestamp(), receivedAt,
                expiresAt, request.headingDegrees(), request.approximateDistanceMeters(),
                request.ttcSeconds(), request.schemaVersion() == null ? 1 : request.schemaVersion());
        HazardEventEntity saved = repository.save(event);
        HazardEventResponse response = HazardEventResponse.from(saved, false);
        publisher.publish(response);
        return response;
    }

    @Transactional(readOnly = true)
    public List<HazardEventResponse> nearby(double latitude, double longitude, double radiusMeters,
                                            Instant since, HazardSeverity minimumSeverity,
                                            Set<HazardType> eventTypes, Integer requestedLimit) {
        validateCoordinates(latitude, longitude);
        if (!Double.isFinite(radiusMeters) || radiusMeters <= 0d
                || radiusMeters > properties.maximumNearbyRadiusMeters()) {
            throw new InvalidHazardRequestException(List.of("radiusMeters must be finite, positive and <= "
                    + properties.maximumNearbyRadiusMeters()));
        }
        int limit = requestedLimit == null ? properties.maximumNearbyResults()
                : Math.min(requestedLimit, properties.maximumNearbyResults());
        if (limit <= 0) throw new InvalidHazardRequestException(List.of("limit must be positive"));
        Instant now = clock.instant();
        Instant effectiveSince = since == null ? Instant.EPOCH : since;
        HazardSeverity effectiveSeverity = minimumSeverity == null ? HazardSeverity.NORMAL : minimumSeverity;
        Set<HazardType> effectiveTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
        return repository.findNearby(latitude, longitude, radiusMeters, now, effectiveSince,
                        effectiveSeverity, effectiveTypes, limit).stream()
                .filter(event -> event.expiresAt().isAfter(now))
                .map(event -> HazardEventResponse.from(event, false))
                .toList();
    }

    private static void validateCoordinates(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90d || latitude > 90d) {
            throw new InvalidHazardRequestException(List.of("latitude must be finite and in [-90,90]"));
        }
        if (!Double.isFinite(longitude) || longitude < -180d || longitude > 180d) {
            throw new InvalidHazardRequestException(List.of("longitude must be finite and in [-180,180]"));
        }
    }

    private static boolean sameIdentity(HazardEventEntity event, HazardEventDto request, HazardType type) {
        return event.anonymousSourceId().equals(request.vehicleId())
                && event.hazardType() == type
                && Double.compare(event.location().getY(), request.latitude()) == 0
                && Double.compare(event.location().getX(), request.longitude()) == 0;
    }

    /** Spatial/time matches merge only with two matching observation cues, limiting herd collapse. */
    private static boolean hasMatchingObservation(HazardEventEntity event, HazardEventDto request) {
        if (event.headingDegrees() == null || request.headingDegrees() == null
                || event.approximateDistanceMeters() == null
                || request.approximateDistanceMeters() == null) return false;
        float headingDelta = Math.abs(event.headingDegrees() - request.headingDegrees());
        headingDelta = Math.min(headingDelta, 360f - headingDelta);
        float distanceDelta = Math.abs(event.approximateDistanceMeters()
                - request.approximateDistanceMeters());
        return headingDelta <= DEDUP_HEADING_TOLERANCE_DEGREES
                && distanceDelta <= DEDUP_DISTANCE_TOLERANCE_METERS;
    }
}
