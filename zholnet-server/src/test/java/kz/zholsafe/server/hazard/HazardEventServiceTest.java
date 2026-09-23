package kz.zholsafe.server.hazard;

import kz.zholsafe.server.config.ZholNetProperties;
import kz.zholsafe.server.websocket.HazardNotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HazardEventServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final GeometryFactory GEOMETRY =
            new GeometryFactory(new PrecisionModel(), 4326);

    @Mock HazardEventRepository repository;
    @Mock HazardNotificationPublisher publisher;

    private HazardEventService service;

    @BeforeEach
    void setUp() {
        ZholNetProperties properties = new ZholNetProperties(Duration.ofMinutes(30),
                Duration.ofMinutes(5), Duration.ofSeconds(8), 12d, 10_000d, 2,
                "/ws/hazards", "/topic/hazards");
        service = new HazardEventService(repository, publisher, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void validEventAcceptedPublishedAndTtlUsesServerReceiveTime() {
        HazardEventDto request = request("e-1", "source-a", "HORSE", 43.24, 76.91,
                NOW.minus(Duration.ofDays(30)), 90f, 40f);
        when(repository.findByEventId("e-1")).thenReturn(Optional.empty());
        when(repository.findConservativeDuplicate(any(), any(), anyDouble(), anyDouble(),
                anyDouble(), any(), any())).thenReturn(Optional.empty());

        HazardEventResponse response = service.accept(request);

        assertFalse(response.deduplicated());
        assertEquals(NOW, response.receivedTimestamp());
        assertEquals(NOW.plus(Duration.ofMinutes(30)), response.expiresAt());
        assertEquals(request.timestamp(), response.sourceTimestamp());
        verify(publisher).publish(response);
    }

    @Test
    void invalidLatitudeLongitudeAndNonFiniteValuesAreRejected() {
        assertThrows(InvalidHazardRequestException.class, () -> service.accept(
                request("bad-lat", "s", "HORSE", 91d, 76d, NOW, 90f, 40f)));
        assertThrows(InvalidHazardRequestException.class, () -> service.accept(
                request("bad-lon", "s", "HORSE", 43d, 181d, NOW, 90f, 40f)));
        HazardEventDto nan = new HazardEventDto("nan", "s", "HORSE", Float.NaN, 0.8f,
                43d, 76d, NOW, "ACTIVE", null);
        assertThrows(InvalidHazardRequestException.class, () -> service.accept(nan));
        verify(repository, never()).save(any());
    }

    @Test
    void expiredEventsAreDefensivelyExcludedFromNearbyResults() {
        HazardEventEntity expired = entity("old", NOW.minusSeconds(1));
        HazardEventEntity active = entity("active", NOW.plusSeconds(60));
        when(repository.findNearby(anyDouble(), anyDouble(), anyDouble(), any(), any(), any(),
                any(), anyInt())).thenReturn(List.of(expired, active));

        List<HazardEventResponse> result = service.nearby(43.24, 76.91, 500d,
                null, null, Set.of(), null);

        assertEquals(List.of("active"), result.stream().map(HazardEventResponse::eventId).toList());
    }

    @Test
    void nearbyPreservesDatabaseOrderingOutsideRadiusAbsenceAndEnforcesLimit() {
        when(repository.findNearby(anyDouble(), anyDouble(), anyDouble(), any(), any(), any(),
                any(), anyInt())).thenReturn(List.of(entity("near", NOW.plusSeconds(60)),
                entity("farther", NOW.plusSeconds(60))));

        List<HazardEventResponse> ordered = service.nearby(43.24, 76.91, 1000d,
                NOW.minusSeconds(60), HazardSeverity.CAUTION, Set.of(HazardType.HORSE), 99);
        assertEquals(List.of("near", "farther"), ordered.stream()
                .map(HazardEventResponse::eventId).toList());
        verify(repository).findNearby(43.24, 76.91, 1000d, NOW, NOW.minusSeconds(60),
                HazardSeverity.CAUTION, Set.of(HazardType.HORSE), 2);

        when(repository.findNearby(anyDouble(), anyDouble(), eq(10d), any(), any(), any(),
                any(), anyInt())).thenReturn(List.of());
        assertTrue(service.nearby(43.24, 76.91, 10d, null, null, Set.of(), 1).isEmpty());
    }

    @Test
    void maximumRadiusIsEnforced() {
        assertThrows(InvalidHazardRequestException.class,
                () -> service.nearby(43.24, 76.91, 10_001d, null, null, Set.of(), null));
        verify(repository, never()).findNearby(anyDouble(), anyDouble(), anyDouble(), any(),
                any(), any(), any(), anyInt());
    }

    @Test
    void matchingObservationIsDeduplicatedAndPublishedAsUpdate() {
        HazardEventEntity existing = entity("original", NOW.plusSeconds(60));
        when(repository.findByEventId("frame-2")).thenReturn(Optional.empty());
        when(repository.findConservativeDuplicate(any(), any(), anyDouble(), anyDouble(),
                anyDouble(), any(), any())).thenReturn(Optional.of(existing));

        HazardEventResponse result = service.accept(request("frame-2", "source-a", "HORSE",
                43.24, 76.91, NOW, 92f, 43f));

        assertTrue(result.deduplicated());
        assertEquals("original", result.eventId());
        assertEquals(2, result.reportCount());
        verify(publisher).publish(result);
    }

    @Test
    void distinctNearbyObservationIsNotMerged() {
        HazardEventEntity existing = entity("animal-1", NOW.plusSeconds(60));
        when(repository.findByEventId("animal-2")).thenReturn(Optional.empty());
        when(repository.findConservativeDuplicate(any(), any(), anyDouble(), anyDouble(),
                anyDouble(), any(), any())).thenReturn(Optional.of(existing));

        HazardEventResponse result = service.accept(request("animal-2", "source-a", "HORSE",
                43.24, 76.91, NOW, 160f, 80f));

        assertFalse(result.deduplicated());
        assertEquals("animal-2", result.eventId());
        ArgumentCaptor<HazardEventEntity> saved = ArgumentCaptor.forClass(HazardEventEntity.class);
        verify(repository).save(saved.capture());
        assertEquals(1, saved.getValue().reportCount());
    }

    private static HazardEventDto request(String id, String source, String type, double latitude,
                                          double longitude, Instant sourceTime, float heading,
                                          float distance) {
        return new HazardEventDto(id, source, type, 0.9f, 0.8f, latitude, longitude, sourceTime,
                "ACTIVE", null, 1, "WARNING", heading, distance, 2.5f);
    }

    private static HazardEventEntity entity(String id, Instant expiresAt) {
        var point = GEOMETRY.createPoint(new Coordinate(76.91, 43.24));
        return new HazardEventEntity(id, "source-a", HazardType.HORSE, HazardSeverity.WARNING,
                0.9f, 0.8f, point, NOW.minusSeconds(5), NOW.minusSeconds(1), expiresAt,
                90f, 40f, 2.5f, 1);
    }
}
