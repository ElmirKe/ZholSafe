package kz.zholsafe.server.hazard;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Native PostGIS queries keep all metre-based distance filtering inside PostgreSQL. */
public class HazardEventSpatialRepositoryImpl implements HazardEventSpatialRepository {
    private static final String QUERY_POINT =
            "CAST(ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326) AS geography)";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<HazardEventEntity> findNearby(double latitude, double longitude, double radiusMeters,
                                              Instant now, Instant since,
                                              HazardSeverity minimumSeverity,
                                              Set<HazardType> eventTypes, int limit) {
        StringBuilder sql = new StringBuilder("SELECT h.* FROM hazard_event h ")
                .append("WHERE h.expires_at > :now ")
                .append("AND h.received_at >= :since ")
                .append("AND h.severity_rank >= :minimumSeverity ")
                .append("AND ST_DWithin(h.location, ").append(QUERY_POINT).append(", :radiusMeters) ");
        List<HazardType> orderedTypes = eventTypes.stream().sorted().toList();
        if (!orderedTypes.isEmpty()) {
            sql.append("AND h.hazard_type IN (");
            for (int i = 0; i < orderedTypes.size(); i++) {
                if (i > 0) sql.append(",");
                sql.append(":type").append(i);
            }
            sql.append(") ");
        }
        sql.append("ORDER BY ST_Distance(h.location, ").append(QUERY_POINT)
                .append(") ASC, h.received_at DESC, h.event_id ASC");

        Query query = baseQuery(sql.toString(), latitude, longitude, radiusMeters)
                .setParameter("now", now)
                .setParameter("since", since)
                .setParameter("minimumSeverity", minimumSeverity.rank())
                .setMaxResults(limit);
        for (int i = 0; i < orderedTypes.size(); i++) {
            query.setParameter("type" + i, orderedTypes.get(i).name());
        }
        return List.copyOf((List<HazardEventEntity>) query.getResultList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<HazardEventEntity> findConservativeDuplicate(
            String anonymousSourceId, HazardType hazardType, double latitude, double longitude,
            double radiusMeters, Instant receivedAfter, Instant now) {
        String sql = "SELECT h.* FROM hazard_event h "
                + "WHERE h.anonymous_source_id = :sourceId AND h.hazard_type = :hazardType "
                + "AND h.received_at >= :receivedAfter AND h.expires_at > :now "
                + "AND ST_DWithin(h.location, " + QUERY_POINT + ", :radiusMeters) "
                + "ORDER BY ST_Distance(h.location, " + QUERY_POINT
                + ") ASC, h.received_at DESC, h.event_id ASC";
        Query query = baseQuery(sql, latitude, longitude, radiusMeters)
                .setParameter("sourceId", anonymousSourceId)
                .setParameter("hazardType", hazardType.name())
                .setParameter("receivedAfter", receivedAfter)
                .setParameter("now", now)
                .setMaxResults(1);
        List<HazardEventEntity> results = new ArrayList<>((List<HazardEventEntity>) query.getResultList());
        return results.stream().findFirst();
    }

    private Query baseQuery(String sql, double latitude, double longitude, double radiusMeters) {
        return entityManager.createNativeQuery(sql, HazardEventEntity.class)
                .setParameter("latitude", latitude)
                .setParameter("longitude", longitude)
                .setParameter("radiusMeters", radiusMeters);
    }
}
