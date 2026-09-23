package kz.zholsafe.server.hazard;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface HazardEventRepository extends JpaRepository<HazardEventEntity, Long>,
        HazardEventSpatialRepository {

    Optional<HazardEventEntity> findByEventId(String eventId);

    @Modifying
    @Query("delete from HazardEventEntity h where h.expiresAt <= :cutoff")
    int deleteExpired(Instant cutoff);
}
