package kz.zholsafe.risk;

import kz.zholsafe.config.RoadRiskConfig;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.physical.DistanceEstimate;
import kz.zholsafe.physical.DistanceMethod;
import kz.zholsafe.physical.EvidenceQuality;
import kz.zholsafe.physical.PhysicalEstimationSnapshot;
import kz.zholsafe.physical.PhysicalObjectEstimate;
import kz.zholsafe.physical.PhysicalReason;
import kz.zholsafe.physical.RangeRateEstimate;
import kz.zholsafe.physical.TtcEstimate;
import kz.zholsafe.physical.TtcMethod;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;
import kz.zholsafe.tracking.TrackState;
import kz.zholsafe.tracking.TrackView;
import kz.zholsafe.tracking.TrackedObject;
import kz.zholsafe.trajectory.ObjectTrajectory;
import kz.zholsafe.trajectory.ScaleChange;
import kz.zholsafe.trajectory.TrajectoryStatus;
import kz.zholsafe.trajectory.TrajectoryQuality;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.Set;

/**
 * Stateless Stage 4.2 ROAD-ONLY snapshot evaluator. Preserves Stage 0 RiskEngine and legacy
 * BaselineRiskEngine for old RiskInput consumers; never consumes DriverState or creates alerts.
 * All scores are bounded engineering severity, NOT collision probabilities or safety claims.
 */
public final class RoadRiskEngine implements RoadRiskEvaluator {
    private final RoadRiskConfig config;

    public RoadRiskEngine(RoadRiskConfig config) { this.config = Objects.requireNonNull(config, "config"); }

    @Override
    public RoadRiskSnapshot evaluate(TrackingSnapshot tracking, TrajectorySnapshot trajectory,
                                     PhysicalEstimationSnapshot physical) {
        Objects.requireNonNull(tracking, "tracking");
        Objects.requireNonNull(trajectory, "trajectory");
        Objects.requireNonNull(physical, "physical");
        long ts = tracking.frameTimestampNanos();
        if (!tracking.available()) {
            return unavailable(ts, tracking.status() == TrackingSnapshot.Status.NOT_STARTED
                    ? RoadRiskSnapshot.Status.NOT_STARTED : RoadRiskSnapshot.Status.TRACKING_UNAVAILABLE,
                    tracking, trajectory, physical);
        }
        if (!trajectory.available()) {
            return unavailable(ts, RoadRiskSnapshot.Status.TRAJECTORY_UNAVAILABLE, tracking, trajectory, physical);
        }
        if (!physical.available()) {
            return unavailable(ts, RoadRiskSnapshot.Status.PHYSICAL_UNAVAILABLE, tracking, trajectory, physical);
        }
        if (ts != trajectory.frameTimestampNanos() || ts != physical.frameTimestampNanos()
                || tracking.uprightWidth() != trajectory.uprightWidth()
                || tracking.uprightWidth() != physical.uprightWidth()
                || tracking.uprightHeight() != trajectory.uprightHeight()
                || tracking.uprightHeight() != physical.uprightHeight()) {
            return unavailable(ts, RoadRiskSnapshot.Status.INVALID_TIMESTAMP, tracking, trajectory, physical);
        }
        Map<Integer, ObjectTrajectory> paths = new HashMap<>();
        for (ObjectTrajectory path : trajectory.objects()) {
            if (paths.putIfAbsent(path.trackId(), path) != null) {
                return unavailable(ts, RoadRiskSnapshot.Status.ENGINE_ERROR, tracking, trajectory, physical);
            }
        }
        Map<Integer, PhysicalObjectEstimate> depths = new HashMap<>();
        for (PhysicalObjectEstimate depth : physical.objects()) {
            if (depths.putIfAbsent(depth.trackId(), depth) != null) {
                return unavailable(ts, RoadRiskSnapshot.Status.ENGINE_ERROR, tracking, trajectory, physical);
            }
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (TrackView view : tracking.tracks()) {
            TrackedObject object = view.object();
            int id = object.trackId();
            ObjectTrajectory path = paths.get(id);
            PhysicalObjectEstimate depth = depths.get(id);
            if (!ids.add(id) || path == null || depth == null
                    || path.objectClass() != object.objectClass() || depth.objectClass() != object.objectClass()
                    || !path.box().equals(object.box()) || depth.trackState() != view.state()) {
                return unavailable(ts, RoadRiskSnapshot.Status.ENGINE_ERROR, tracking, trajectory, physical);
            }
            if (view.state() == TrackState.CONFIRMED && view.missedFrames() == 0) {
                if (object.timestampNanos() != ts || path.timestampNanos() != ts
                        || depth.timestampNanos() != ts || view.history().isEmpty()
                        || path.status() == TrajectoryStatus.INVALID_TIMESTAMPS
                        || path.status() == TrajectoryStatus.TRACK_NOT_CURRENT
                        || view.history().get(view.history().size() - 1).timestampNanos() != ts
                        || !view.history().get(view.history().size() - 1).box().equals(object.box())) {
                    return unavailable(ts, RoadRiskSnapshot.Status.INVALID_TIMESTAMP, tracking, trajectory, physical);
                }
                long previous = 0;
                for (var observation : view.history()) {
                    if (observation.timestampNanos() <= previous) {
                        return unavailable(ts, RoadRiskSnapshot.Status.INVALID_TIMESTAMP, tracking, trajectory, physical);
                    }
                    previous = observation.timestampNanos();
                }
            }
            if ((depth.metricTtc().available() && depth.metricTtc().method() != TtcMethod.METRIC_RANGE)
                    || (depth.imageScaleTtc().available()
                    && depth.imageScaleTtc().method() != TtcMethod.IMAGE_SCALE)) {
                return unavailable(ts, RoadRiskSnapshot.Status.ENGINE_ERROR, tracking, trajectory, physical);
            }
            boolean metricPresent = depth.metricTtc().available();
            boolean opticalPresent = depth.imageScaleTtc().available();
            if ((depth.selectedTtc().available()
                    && ((!depth.selectedTtc().equals(depth.metricTtc())
                    && !depth.selectedTtc().equals(depth.imageScaleTtc()))
                    || (depth.selectedTtc().method() == TtcMethod.IMAGE_SCALE && metricPresent)))
                    || (!depth.selectedTtc().available() && (metricPresent || opticalPresent)
                    && !(metricPresent && opticalPresent
                    && depth.selectedTtc().reason() == PhysicalReason.CONFLICTING_ESTIMATES))) {
                return unavailable(ts, RoadRiskSnapshot.Status.ENGINE_ERROR, tracking, trajectory, physical);
            }
        }
        if (ids.size() != paths.size() || ids.size() != depths.size()) {
            return unavailable(ts, RoadRiskSnapshot.Status.ENGINE_ERROR, tracking, trajectory, physical);
        }
        List<ObjectRiskAssessment> objects = new ArrayList<>();
        for (TrackView view : tracking.tracks()) {
            if (view.state() != TrackState.CONFIRMED || view.missedFrames() != 0) continue;
            try {
                objects.add(assess(view.object(), paths.get(view.object().trackId()),
                        depths.get(view.object().trackId()), tracking.uprightWidth(), tracking.uprightHeight(), ts));
            } catch (IllegalArgumentException ex) {
                return unavailable(ts, RoadRiskSnapshot.Status.ENGINE_ERROR, tracking, trajectory, physical);
            }
        }
        RiskLevel highest = RiskLevel.NORMAL;
        int highestId = 0;
        double highestScore = -1d;
        for (ObjectRiskAssessment object : objects) {
            if (object.level().ordinal() > highest.ordinal()
                    || (object.level() == highest && object.level() != RiskLevel.NORMAL
                    && (object.engineeringScore() > highestScore
                    || (object.engineeringScore() == highestScore && object.trackId() < highestId)))) {
                highest = object.level();
                highestId = object.trackId();
                highestScore = object.engineeringScore();
            }
        }
        return new RoadRiskSnapshot(ts, tracking.uprightWidth(), tracking.uprightHeight(),
                RoadRiskSnapshot.Status.READY, tracking.status(), trajectory.status(), physical.status(),
                objects, Optional.of(highest), highestId == 0 ? OptionalInt.empty() : OptionalInt.of(highestId));
    }

    private ObjectRiskAssessment assess(TrackedObject object, ObjectTrajectory path,
                                        PhysicalObjectEstimate physical, int w, int h, long ts) {
        LinkedHashSet<RiskReason> reasons = new LinkedHashSet<>();
        List<RiskEvidence> evidence = new ArrayList<>();
        CorridorRelation relation = config.corridor().classify(object.box(), w, h,
                config.nearMarginFraction(), config.centralHalfWidthFraction());
        EvidenceQuality quality = EvidenceQuality.LOW; // image geometry and class are uncalibrated
        switch (relation) {
            case OUTSIDE -> evidence.add(flag(RiskEvidenceType.OBJECT_OUTSIDE_CORRIDOR, EvidenceSource.TRACKING));
            case NEAR -> {
                evidence.add(flag(RiskEvidenceType.OBJECT_NEAR_CORRIDOR, EvidenceSource.TRACKING));
                reasons.add(RiskReason.OBJECT_NEAR_DRIVING_CORRIDOR);
            }
            case INTERSECTING, CENTRAL -> {
                evidence.add(flag(RiskEvidenceType.OBJECT_IN_CORRIDOR, EvidenceSource.TRACKING));
                reasons.add(RiskReason.OBJECT_IN_DRIVING_CORRIDOR);
                if (relation == CorridorRelation.CENTRAL) {
                    evidence.add(flag(RiskEvidenceType.OBJECT_CENTRAL, EvidenceSource.TRACKING));
                }
            }
        }
        // Low detector confidence cannot assert active risk, even when a confirmed ID exists.
        if (object.confidence() < config.minimumDetectionConfidence()) {
            reasons.add(RiskReason.LOW_CONFIDENCE_ONLY);
            evidence.add(RiskEvidence.flag(RiskEvidenceType.LOW_QUALITY_EVIDENCE,
                    EvidenceSource.TRACKING, EvidenceQuality.LOW));
            return new ObjectRiskAssessment(object.trackId(), object.objectClass(), ts, RiskLevel.NORMAL, 0d,
                    quality, new RiskComponents(0, 0, 0, 0, 0, 0), List.copyOf(reasons), evidence);
        }
        RoadRiskConfig.Contributions c = config.contributions();
        boolean inside = relation == CorridorRelation.CENTRAL || relation == CorridorRelation.INTERSECTING;
        double corridor = switch (relation) {
            case CENTRAL -> c.central();
            case INTERSECTING -> c.intersecting();
            case NEAR -> c.near();
            case OUTSIDE -> 0d;
        };
        boolean growth = false, moving = false, predicted = false;
        double trajectory = 0d;
        if (path.available() && path.quality() == TrajectoryQuality.FIT_ACCEPTED) {
            if (path.scale().change() == ScaleChange.GROWING) {
                growth = true;
                trajectory += c.apparentGrowth();
                reasons.add(RiskReason.APPARENT_APPROACH);
                evidence.add(RiskEvidence.value(RiskEvidenceType.APPARENT_APPROACH,
                        EvidenceSource.IMAGE_TRAJECTORY, EvidenceQuality.LOW,
                        path.scale().logAreaRatePerSecond(), EvidenceUnit.LOG_AREA_PER_SECOND));
            } else if (path.scale().change() == ScaleChange.SHRINKING) {
                reasons.add(RiskReason.APPARENT_RECEDE);
                evidence.add(flag(RiskEvidenceType.APPARENT_RECEDE, EvidenceSource.IMAGE_TRAJECTORY));
            }
            if (!inside && path.motion().available()) {
                double contactX = ((double) object.box().x1() + object.box().x2()) / (2d * w);
                double contactY = (double) object.box().y2() / h;
                double vx = path.motion().velocityXFrameWidthsPerSecond();
                double vy = path.motion().velocityYFrameHeightsPerSecond();
                moving = Math.abs(vx) >= config.minimumHorizontalSpeedFractionPerSecond()
                        && (config.corridor().centerX() - contactX) * vx > 0d;
                if (moving) {
                    trajectory += c.movingToward();
                    reasons.add(RiskReason.MOVING_TOWARD_CORRIDOR);
                    evidence.add(RiskEvidence.value(RiskEvidenceType.MOVING_TOWARD_CORRIDOR,
                            EvidenceSource.IMAGE_TRAJECTORY, EvidenceQuality.LOW, vx,
                            EvidenceUnit.NORMALIZED_PER_SECOND));
                    double futureX = contactX + vx * config.predictionHorizonSeconds();
                    double futureY = contactY + vy * config.predictionHorizonSeconds();
                    if (config.corridor().containsContact(futureX, futureY)) {
                        predicted = true;
                        trajectory += c.predictedEntry();
                        reasons.add(RiskReason.PREDICTED_CORRIDOR_ENTRY);
                        evidence.add(RiskEvidence.value(RiskEvidenceType.PREDICTED_CORRIDOR_ENTRY,
                                EvidenceSource.IMAGE_TRAJECTORY, EvidenceQuality.LOW,
                                config.predictionHorizonSeconds(), EvidenceUnit.SECONDS));
                    }
                }
            }
        } else {
            evidence.add(RiskEvidence.flag(RiskEvidenceType.TRAJECTORY_UNAVAILABLE,
                    EvidenceSource.IMAGE_TRAJECTORY, EvidenceQuality.UNAVAILABLE));
        }
        trajectory = Math.min(c.trajectoryCap(), trajectory);
        DistanceEstimate distance = physical.distance();
        RangeRateEstimate rate = physical.rangeRate();
        boolean validDistance = distance.available() && distance.timestampNanos() == ts
                && distance.quality().atLeast(config.minimumMetricQuality());
        if (validDistance) {
            quality = distance.quality();
            evidence.add(RiskEvidence.value(RiskEvidenceType.METRIC_DISTANCE_AVAILABLE,
                    EvidenceSource.METRIC_RANGE, distance.quality(), distance.meters(),
                    EvidenceUnit.METERS_OPTICAL_DEPTH));
        } else {
            evidence.add(RiskEvidence.physicalFlag(distance.available()
                            ? RiskEvidenceType.PHYSICAL_LOW_QUALITY
                            : RiskEvidenceType.PHYSICAL_ESTIMATE_UNAVAILABLE,
                    distance.available() ? PhysicalReason.LOW_QUALITY : distance.reason(),
                    distance.available() ? EvidenceQuality.LOW : EvidenceQuality.UNAVAILABLE));
            reasons.add(RiskReason.PHYSICAL_EVIDENCE_UNAVAILABLE);
        }
        boolean closing = validDistance && rate.available() && rate.timestampNanos() == ts
                && rate.quality().atLeast(config.minimumMetricQuality())
                && rate.closingSpeedMps() >= config.minimumClosingMps();
        double closingComponent = 0d;
        if (closing) {
            boolean rapid = rate.closingSpeedMps() >= config.rapidClosingMps();
            closingComponent = rapid ? c.rapidClosing() : c.closing();
            if (distance.method() == DistanceMethod.OBJECT_SIZE) {
                closingComponent *= config.sizePriorQualityFactor();
            }
            reasons.add(rapid ? RiskReason.RAPID_CLOSING : RiskReason.OBJECT_CLOSING);
            evidence.add(RiskEvidence.value(RiskEvidenceType.METRIC_CLOSING,
                    EvidenceSource.METRIC_RANGE, rate.quality(), rate.closingSpeedMps(),
                    EvidenceUnit.METERS_PER_SECOND_RELATIVE));
        }
        TtcEstimate selected = physical.selectedTtc();
        boolean conflict = selected.reason() == PhysicalReason.CONFLICTING_ESTIMATES;
        if (conflict) {
            reasons.add(RiskReason.TTC_ESTIMATES_CONFLICT);
            evidence.add(RiskEvidence.physicalFlag(RiskEvidenceType.TTC_CONFLICT,
                    PhysicalReason.CONFLICTING_ESTIMATES, EvidenceQuality.UNAVAILABLE));
        }
        double ttcComponent = 0d;
        boolean shortMetric = false, shortOptical = false;
        if (!conflict && selected.available() && selected.timestampNanos() == ts) {
            if (selected.method() == TtcMethod.METRIC_RANGE && validDistance && closing
                    && selected.equals(physical.metricTtc())
                    && selected.quality().atLeast(config.minimumMetricQuality())) {
                double seconds = selected.seconds();
                shortMetric = seconds <= config.metricTtc().cautionSeconds();
                double strength = seconds <= config.metricTtc().criticalSeconds() ? c.metricCritical()
                        : seconds <= config.metricTtc().warningSeconds() ? c.metricWarning()
                        : shortMetric ? c.metricCaution() : 0d;
                if (strength > 0d) {
                    strength *= selected.quality() == EvidenceQuality.MEDIUM
                            ? config.metricMediumQualityFactor() : 1d;
                    if (distance.method() == DistanceMethod.OBJECT_SIZE) {
                        strength *= config.sizePriorQualityFactor();
                    }
                    ttcComponent = strength;
                    reasons.add(RiskReason.SHORT_METRIC_TTC);
                    evidence.add(RiskEvidence.value(RiskEvidenceType.METRIC_TTC_SHORT,
                            EvidenceSource.METRIC_TTC, selected.quality(), seconds, EvidenceUnit.SECONDS));
                }
            } else if (selected.method() == TtcMethod.IMAGE_SCALE && growth
                    && selected.equals(physical.imageScaleTtc())
                    && selected.quality() == EvidenceQuality.LOW && (inside || predicted)) {
                double seconds = selected.seconds();
                shortOptical = seconds <= config.opticalTtc().cautionSeconds();
                ttcComponent = seconds <= config.opticalTtc().criticalSeconds() ? c.opticalCritical()
                        : seconds <= config.opticalTtc().warningSeconds() ? c.opticalWarning()
                        : shortOptical ? c.opticalCaution() : 0d;
                if (ttcComponent > 0d) {
                    reasons.add(RiskReason.OPTICAL_EXPANSION);
                    evidence.add(RiskEvidence.value(RiskEvidenceType.IMAGE_SCALE_TTC_SHORT,
                            EvidenceSource.OPTICAL_EXPANSION, EvidenceQuality.LOW,
                            seconds, EvidenceUnit.SECONDS));
                }
            }
        }
        boolean relevant = relation != CorridorRelation.OUTSIDE || moving;
        double area = (((double) object.box().x2() - object.box().x1()) / w)
                * (((double) object.box().y2() - object.box().y1()) / h);
        double appearance = relevant && area >= config.largeBoxAreaFraction()
                ? c.largeAppearance() : 0d;
        if (appearance > 0d) {
            reasons.add(RiskReason.OBJECT_LARGE_IN_FRAME);
            evidence.add(RiskEvidence.value(RiskEvidenceType.LARGE_IN_FRAME,
                    EvidenceSource.TRACKING, EvidenceQuality.LOW, area, EvidenceUnit.NORMALIZED_FRACTION));
        }
        boolean livestock = object.objectClass() == ObjectClass.HORSE
                || object.objectClass() == ObjectClass.COW || object.objectClass() == ObjectClass.CAMEL;
        double classModifier = relevant && livestock ? c.largeClass() : 0d;
        if (classModifier > 0d) {
            reasons.add(RiskReason.LARGE_LIVESTOCK);
            evidence.add(flag(RiskEvidenceType.LARGE_HAZARD_CLASS, EvidenceSource.CLASS_PRIOR));
        }
        RiskComponents components = new RiskComponents(corridor, trajectory, closingComponent,
                ttcComponent, appearance, classModifier);
        double score = components.cappedTotal();
        RiskLevel level = config.scoreBands().levelFor(score);
        // Critical requires geometric conflict AND a short method-labelled TTC, plus physical
        // closing or independent image geometry/growth. SIZE alone cannot authorize critical.
        boolean strongMetric = inside && shortMetric && closing
                && selected.seconds() <= config.metricTtc().criticalSeconds()
                && distance.method() != DistanceMethod.OBJECT_SIZE;
        boolean strongOptical = inside && growth && shortOptical
                && selected.seconds() <= config.opticalTtc().criticalSeconds();
        boolean warningEligible = (inside && (growth || closing || shortMetric || shortOptical))
                || (predicted && (growth || moving));
        if (level == RiskLevel.CRITICAL && !(strongMetric || strongOptical)) level = RiskLevel.WARNING;
        if (level == RiskLevel.WARNING && !warningEligible) level = RiskLevel.CAUTION;
        return new ObjectRiskAssessment(object.trackId(), object.objectClass(), ts, level, score,
                quality, components, List.copyOf(reasons), evidence);
    }

    private static RiskEvidence flag(RiskEvidenceType type, EvidenceSource source) {
        return RiskEvidence.flag(type, source, EvidenceQuality.LOW);
    }

    private static RoadRiskSnapshot unavailable(long ts, RoadRiskSnapshot.Status status,
                                                TrackingSnapshot tracking, TrajectorySnapshot trajectory,
                                                PhysicalEstimationSnapshot physical) {
        return RoadRiskSnapshot.unavailable(ts, status, tracking.status(), trajectory.status(), physical.status());
    }
}
