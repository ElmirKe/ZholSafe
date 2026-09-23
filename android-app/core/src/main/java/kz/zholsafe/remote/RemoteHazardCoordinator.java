package kz.zholsafe.remote;

import kz.zholsafe.location.LocationFix;
import kz.zholsafe.network.ClientResult;
import kz.zholsafe.network.NearbyHazard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Converts poll outcomes into immutable advisory snapshots with a short, bounded UI hold. */
public final class RemoteHazardCoordinator {
    private final RemoteHazardEvaluator evaluator;
    private final RemoteHazardConfig config;
    private final Clock clock;
    private RemoteHazardWarning held;
    private Instant heldLastSeen;

    public RemoteHazardCoordinator(RemoteHazardEvaluator evaluator,
                                   RemoteHazardConfig config, Clock clock) {
        this.evaluator = Objects.requireNonNull(evaluator); this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized RemoteHazardSnapshot accept(Optional<LocationFix> location, long elapsedNanos,
            ClientResult<List<NearbyHazard>> result) {
        Instant now = clock.instant();
        if (result == null || !result.successful()) {
            held = null; heldLastSeen = null;
            return RemoteHazardSnapshot.unavailable(RemoteHazardSnapshot.Status.NETWORK_UNAVAILABLE,
                    now, result == null ? "missing network result" : result.error());
        }
        RemoteHazardSnapshot evaluated = evaluator.evaluate(location, elapsedNanos, result.value());
        if (evaluated.status() != RemoteHazardSnapshot.Status.AVAILABLE) {
            held = null; heldLastSeen = null; return evaluated;
        }
        if (evaluated.selected().isPresent()) {
            held = evaluated.selected().get(); heldLastSeen = now; return evaluated;
        }
        if (held != null && holdValid(now)) {
            return new RemoteHazardSnapshot(RemoteHazardSnapshot.Status.AVAILABLE, now,
                    List.of(held), Optional.of(held), "brief advisory hold");
        }
        held = null; heldLastSeen = null; return evaluated;
    }

    private boolean holdValid(Instant now) {
        return Duration.between(heldLastSeen, now).compareTo(config.warningHoldTime()) <= 0
                && held.expiresAt().isAfter(now)
                && Duration.between(held.receivedTimestamp(), now).compareTo(config.maximumAdvisoryAge()) <= 0;
    }
}
