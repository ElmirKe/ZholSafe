package kz.zholsafe.network;

import kz.zholsafe.location.LocationFix;
import kz.zholsafe.physical.PhysicalEstimationSnapshot;
import kz.zholsafe.physical.PhysicalObjectEstimate;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.risk.ObjectRiskAssessment;
import kz.zholsafe.risk.RiskLevel;
import kz.zholsafe.risk.RoadRiskSnapshot;
import kz.zholsafe.tracking.TrackedObject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pure bridge after local risk evaluation. It performs no I/O and cannot delay local warnings. */
public final class HazardEventBridge {
    public enum Outcome { CREATED, LOCAL_RISK_UNAVAILABLE, BELOW_POLICY, MISSING_LOCATION,
        STALE_LOCATION, TRACK_EVIDENCE_UNAVAILABLE, UNSUPPORTED_CLASS, COOLDOWN_SUPPRESSED }
    public record Result(Outcome outcome, Optional<NetworkHazardEvent> event) {
        public Result { Objects.requireNonNull(outcome); Objects.requireNonNull(event); }
        static Result skip(Outcome outcome) { return new Result(outcome, Optional.empty()); }
    }

    private final PublicationPolicy policy;
    private final AnonymousSourceIdProvider sourceIds;
    private final SourceTimeMapper timeMapper;
    private final LinkedHashMap<Integer, Long> lastPublished = new LinkedHashMap<>();

    public HazardEventBridge(PublicationPolicy policy, AnonymousSourceIdProvider sourceIds,
                             SourceTimeMapper timeMapper) {
        this.policy = Objects.requireNonNull(policy);
        this.sourceIds = Objects.requireNonNull(sourceIds);
        this.timeMapper = Objects.requireNonNull(timeMapper);
    }

    public synchronized Result create(RoadRiskSnapshot risk, TrackingSnapshot tracking,
                                      PhysicalEstimationSnapshot physical,
                                      Optional<LocationFix> location) {
        if (risk == null || !risk.available()) return Result.skip(Outcome.LOCAL_RISK_UNAVAILABLE);
        RiskLevel level = risk.highestLevel().orElse(RiskLevel.NORMAL);
        if (level.ordinal() < policy.minimumLevel().ordinal()) return Result.skip(Outcome.BELOW_POLICY);
        if (location == null || location.isEmpty()) return Result.skip(Outcome.MISSING_LOCATION);
        LocationFix fix = location.get();
        if (!fix.freshAt(risk.frameTimestampNanos(), policy.maximumLocationAge().toNanos())) {
            return Result.skip(Outcome.STALE_LOCATION);
        }
        if (risk.highestRiskTrackId().isEmpty() || tracking == null || !tracking.available()) {
            return Result.skip(Outcome.TRACK_EVIDENCE_UNAVAILABLE);
        }
        int trackId = risk.highestRiskTrackId().getAsInt();
        Optional<ObjectRiskAssessment> assessment = risk.objects().stream()
                .filter(v -> v.trackId() == trackId).findFirst();
        Optional<TrackedObject> track = tracking.confirmedObjects().stream()
                .filter(v -> v.trackId() == trackId && v.timestampNanos() == risk.frameTimestampNanos()).findFirst();
        if (assessment.isEmpty() || track.isEmpty()) return Result.skip(Outcome.TRACK_EVIDENCE_UNAVAILABLE);
        Optional<NetworkHazardType> type = HazardClassMapper.fromVerifiedDetector(assessment.get().objectClass());
        if (type.isEmpty()) return Result.skip(Outcome.UNSUPPORTED_CLASS);
        Long previous = lastPublished.get(trackId);
        if (previous != null && risk.frameTimestampNanos() - previous < policy.cooldown().toNanos()) {
            return Result.skip(Outcome.COOLDOWN_SUPPRESSED);
        }

        Float distance = null;
        Float ttc = null;
        if (physical != null && physical.available()) {
            Optional<PhysicalObjectEstimate> estimate = physical.objects().stream()
                    .filter(v -> v.trackId() == trackId && v.timestampNanos() == risk.frameTimestampNanos()).findFirst();
            if (estimate.isPresent()) {
                if (estimate.get().distance().available()) distance = (float) estimate.get().distance().meters();
                if (estimate.get().selectedTtc().available()) ttc = (float) estimate.get().selectedTtc().seconds();
            }
        }
        Float heading = fix.bearingDegrees().isPresent() ? (float) fix.bearingDegrees().getAsDouble() : null;
        Instant sourceTime = timeMapper.toWallClock(risk.frameTimestampNanos());
        NetworkHazardEvent event = new NetworkHazardEvent(UUID.randomUUID().toString(), 1,
                sourceIds.current(), type.get(), severity(level), track.get().confidence(),
                (float) assessment.get().engineeringScore(), fix.latitude(), fix.longitude(),
                sourceTime, "ACTIVE", heading, distance, ttc);
        lastPublished.put(trackId, risk.frameTimestampNanos());
        while (lastPublished.size() > policy.suppressionTrackCapacity()) {
            Integer oldest = lastPublished.keySet().iterator().next();
            lastPublished.remove(oldest);
        }
        return new Result(Outcome.CREATED, Optional.of(event));
    }

    private static NetworkSeverity severity(RiskLevel level) {
        return switch (level) {
            case NORMAL -> NetworkSeverity.NORMAL;
            case CAUTION -> NetworkSeverity.CAUTION;
            case WARNING -> NetworkSeverity.WARNING;
            case CRITICAL -> NetworkSeverity.CRITICAL;
        };
    }
}
