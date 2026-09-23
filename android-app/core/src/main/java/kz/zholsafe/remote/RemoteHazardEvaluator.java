package kz.zholsafe.remote;

import kz.zholsafe.location.LocationFix;
import kz.zholsafe.network.NearbyHazard;
import kz.zholsafe.network.NetworkSeverity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Deterministic remote-advisory evaluator. It has no dependency on any local risk engine. */
public final class RemoteHazardEvaluator {
    private final RemoteHazardConfig config;
    private final RecentPublishedEventRegistry ownEvents;
    private final Clock clock;

    public RemoteHazardEvaluator(RemoteHazardConfig config,
                                 RecentPublishedEventRegistry ownEvents, Clock clock) {
        this.config = Objects.requireNonNull(config); this.ownEvents = Objects.requireNonNull(ownEvents);
        this.clock = Objects.requireNonNull(clock);
    }

    public RemoteHazardSnapshot evaluate(Optional<LocationFix> location, long nowElapsedNanos,
                                         List<NearbyHazard> hazards) {
        Instant now = clock.instant();
        if (location == null || location.isEmpty()) {
            return RemoteHazardSnapshot.unavailable(RemoteHazardSnapshot.Status.LOCATION_UNAVAILABLE,
                    now, "vehicle location unavailable");
        }
        LocationFix fix = location.get();
        if (!fix.freshAt(nowElapsedNanos, config.maximumVehicleLocationAge().toNanos())) {
            return RemoteHazardSnapshot.unavailable(RemoteHazardSnapshot.Status.LOCATION_STALE,
                    now, "vehicle location stale");
        }
        List<RemoteHazardWarning> warnings = new ArrayList<>();
        for (NearbyHazard hazard : Objects.requireNonNull(hazards)) {
            evaluateOne(fix, hazard, now).ifPresent(warnings::add);
        }
        warnings.sort(priority());
        List<RemoteHazardWarning> immutable = List.copyOf(warnings);
        return new RemoteHazardSnapshot(RemoteHazardSnapshot.Status.AVAILABLE, now, immutable,
                immutable.stream().findFirst(), "");
    }

    private Optional<RemoteHazardWarning> evaluateOne(LocationFix fix, NearbyHazard hazard, Instant now) {
        if (hazard == null || ownEvents.contains(hazard.eventId()) || !hazard.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        Duration age = Duration.between(hazard.receivedTimestamp(), now);
        if (age.isNegative() || age.compareTo(config.maximumAdvisoryAge()) > 0) return Optional.empty();
        double distance = GeoMath.distanceMeters(fix.latitude(), fix.longitude(),
                hazard.latitude(), hazard.longitude());
        if (!Double.isFinite(distance) || distance > config.fetchRadiusMeters()) return Optional.empty();

        double bearing = GeoMath.initialBearingDegrees(fix.latitude(), fix.longitude(),
                hazard.latitude(), hazard.longitude());
        BearingRelation relation = BearingRelation.UNKNOWN;
        Double difference = null;
        if (fix.bearingDegrees().isPresent()) {
            difference = GeoMath.absoluteAngularDifference(fix.bearingDegrees().getAsDouble(), bearing);
            relation = difference <= config.forwardConeDegrees() ? BearingRelation.AHEAD
                    : difference >= config.behindThresholdDegrees() ? BearingRelation.BEHIND
                    : BearingRelation.LATERAL;
            if (relation == BearingRelation.BEHIND) return Optional.empty();
        }

        List<RemoteHazardReason> reasons = new ArrayList<>();
        reasons.add(RemoteHazardReason.FRESH); reasons.add(RemoteHazardReason.WITHIN_FETCH_RADIUS);
        reasons.add(distance <= config.nearDistanceMeters() ? RemoteHazardReason.NEAR
                : distance <= config.mediumDistanceMeters() ? RemoteHazardReason.MEDIUM_DISTANCE
                : RemoteHazardReason.FAR);
        reasons.add(switch (relation) {
            case AHEAD -> RemoteHazardReason.AHEAD;
            case LATERAL -> RemoteHazardReason.LATERAL;
            case UNKNOWN -> RemoteHazardReason.HEADING_UNAVAILABLE;
            case BEHIND -> throw new IllegalStateException("behind already suppressed");
        });
        if (hazard.severity().ordinal() >= NetworkSeverity.WARNING.ordinal()) {
            reasons.add(RemoteHazardReason.HIGH_NETWORK_SEVERITY);
        }
        RemoteHazardWarningLevel level = level(distance, relation, hazard.severity());
        return Optional.of(new RemoteHazardWarning(hazard.eventId(), hazard.hazardType(),
                hazard.severity(), distance, age, relation, bearing, difference, level, reasons,
                hazard.sourceTimestamp(), hazard.receivedTimestamp(), hazard.expiresAt()));
    }

    private RemoteHazardWarningLevel level(double distance, BearingRelation relation, NetworkSeverity severity) {
        boolean elevated = severity.ordinal() >= NetworkSeverity.WARNING.ordinal();
        if (relation == BearingRelation.AHEAD && distance <= config.nearDistanceMeters() && elevated) {
            return RemoteHazardWarningLevel.WARNING;
        }
        if (relation == BearingRelation.AHEAD && distance <= config.mediumDistanceMeters()
                && severity.ordinal() >= NetworkSeverity.CAUTION.ordinal()) {
            return RemoteHazardWarningLevel.CAUTION;
        }
        if (relation == BearingRelation.UNKNOWN && distance <= config.nearDistanceMeters() && elevated) {
            return RemoteHazardWarningLevel.CAUTION;
        }
        return RemoteHazardWarningLevel.INFO;
    }

    private static Comparator<RemoteHazardWarning> priority() {
        return Comparator.comparingInt((RemoteHazardWarning w) -> w.level().ordinal()).reversed()
                .thenComparingInt(w -> w.bearingRelation() == BearingRelation.AHEAD ? 0 : 1)
                .thenComparingDouble(RemoteHazardWarning::distanceMeters)
                .thenComparing(RemoteHazardWarning::age)
                .thenComparing((RemoteHazardWarning w) -> w.networkSeverity().ordinal(), Comparator.reverseOrder())
                .thenComparing(RemoteHazardWarning::eventId);
    }
}
