package kz.zholsafe.server.hazard;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HazardEventSpatialRepositoryTest {
    @Mock EntityManager entityManager;
    @Mock Query query;

    private HazardEventSpatialRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new HazardEventSpatialRepositoryImpl();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);
        when(entityManager.createNativeQuery(anyString(), eq(HazardEventEntity.class))).thenReturn(query);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.setMaxResults(org.mockito.ArgumentMatchers.anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
    }

    @Test
    void nearbyUsesPostgisExpiryMetersDeterministicOrderingAndBoundedLimit() {
        repository.findNearby(43.24, 76.91, 500d, Instant.parse("2026-09-23T12:00:00Z"),
                Instant.EPOCH, HazardSeverity.CAUTION, Set.of(HazardType.HORSE), 25);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture(), eq(HazardEventEntity.class));
        String statement = sql.getValue();
        assertTrue(statement.contains("h.expires_at > :now"));
        assertTrue(statement.contains("h.received_at >= :since"));
        assertTrue(statement.contains("ST_DWithin"));
        assertTrue(statement.contains("AS geography"));
        assertTrue(statement.contains("ORDER BY ST_Distance"));
        assertTrue(statement.contains("h.received_at DESC, h.event_id ASC"));
        verify(query).setMaxResults(25);
    }

    @Test
    void dedupQueryRequiresSameAnonymousSourceTypeTimeWindowAndPostgisRadius() {
        repository.findConservativeDuplicate("rotating-source", HazardType.HORSE, 43.24, 76.91,
                12d, Instant.parse("2026-09-23T11:59:52Z"),
                Instant.parse("2026-09-23T12:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture(), eq(HazardEventEntity.class));
        String statement = sql.getValue();
        assertTrue(statement.contains("h.anonymous_source_id = :sourceId"));
        assertTrue(statement.contains("h.hazard_type = :hazardType"));
        assertTrue(statement.contains("h.received_at >= :receivedAfter"));
        assertTrue(statement.contains("ST_DWithin"));
        verify(query).setMaxResults(1);
    }
}
