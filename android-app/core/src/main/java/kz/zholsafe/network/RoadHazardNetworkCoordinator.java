package kz.zholsafe.network;

import kz.zholsafe.location.LocationProvider;
import kz.zholsafe.physical.PhysicalEstimationSnapshot;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.risk.RoadRiskSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * One-way adapter invoked only after local risk is available. Work is constant-time; HTTP is
 * delegated to the bounded asynchronous publisher. The supplied local snapshot is returned intact.
 */
public final class RoadHazardNetworkCoordinator {
    public record Dispatch(RoadRiskSnapshot localRisk, HazardEventBridge.Outcome bridgeOutcome,
                           Optional<QueuedHazardPublisher.EnqueueResult> enqueueResult) {}
    private final LocationProvider locations;
    private final HazardEventBridge bridge;
    private final QueuedHazardPublisher publisher;

    public RoadHazardNetworkCoordinator(LocationProvider locations, HazardEventBridge bridge,
                                        QueuedHazardPublisher publisher) {
        this.locations = Objects.requireNonNull(locations);
        this.bridge = Objects.requireNonNull(bridge);
        this.publisher = Objects.requireNonNull(publisher);
    }

    public Dispatch afterLocalRisk(RoadRiskSnapshot risk, TrackingSnapshot tracking,
                                   PhysicalEstimationSnapshot physical) {
        HazardEventBridge.Result mapped = bridge.create(risk, tracking, physical, locations.latestFix());
        Optional<QueuedHazardPublisher.EnqueueResult> queued = mapped.event().map(publisher::enqueue);
        return new Dispatch(risk, mapped.outcome(), queued);
    }
}
