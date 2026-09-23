package kz.zholsafe.server.hazard;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Optional storage cleanup; query correctness independently excludes expired rows. */
@Component
public class ExpiredHazardCleanup {
    private final HazardEventRepository repository;
    private final Clock clock;

    public ExpiredHazardCleanup(HazardEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${zholnet.hazards.cleanup-interval:PT5M}")
    @Transactional
    public int removeExpired() {
        return repository.deleteExpired(clock.instant());
    }
}
