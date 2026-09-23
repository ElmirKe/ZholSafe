package kz.zholsafe.pipeline;

import kz.zholsafe.model.HazardEvent;

/**
 * ZholNet output port. Implementations (Stage 5/6) submit via HTTPS REST, queue while offline
 * according to {@link kz.zholsafe.config.NetworkConfig#offlineQueueCapacity()}, and must NEVER
 * block or fail the local alert path. A no-op implementation is valid when ZholNet is disabled.
 */
public interface HazardEventPublisher {

    void publish(HazardEvent event);

    HazardEventPublisher NO_OP = event -> { };
}
