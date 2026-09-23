package kz.zholsafe.remote;

import kz.zholsafe.network.NetworkHazardType;
import kz.zholsafe.network.NetworkSeverity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Vehicle-to-reported-hazard advisory. It deliberately contains no Vehicle-B TTC. */
public record RemoteHazardWarning(String eventId, NetworkHazardType hazardType,
        NetworkSeverity networkSeverity, double distanceMeters, Duration age,
        BearingRelation bearingRelation, Double bearingToHazardDegrees,
        Double absoluteHeadingDifferenceDegrees, RemoteHazardWarningLevel level,
        List<RemoteHazardReason> reasons, Instant sourceTimestamp,
        Instant receivedTimestamp, Instant expiresAt) {
    public RemoteHazardWarning {
        Objects.requireNonNull(eventId); Objects.requireNonNull(hazardType);
        Objects.requireNonNull(networkSeverity); Objects.requireNonNull(age);
        Objects.requireNonNull(bearingRelation); Objects.requireNonNull(level);
        reasons = List.copyOf(Objects.requireNonNull(reasons));
        Objects.requireNonNull(sourceTimestamp); Objects.requireNonNull(receivedTimestamp);
        Objects.requireNonNull(expiresAt);
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0 || age.isNegative() || reasons.isEmpty()) {
            throw new IllegalArgumentException("invalid remote advisory");
        }
    }
}
