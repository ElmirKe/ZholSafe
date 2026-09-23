package kz.zholsafe.risk;

import kz.zholsafe.config.RiskConfig;
import kz.zholsafe.driver.DriverState;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.tracking.MovementClass;
import kz.zholsafe.tracking.TrackedObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * STAGE 0 BASELINE — intentionally simple, fully explainable, configuration-driven.
 *
 * <p>This is NOT the final Stage 4 algorithm. It exists so that the contract is exercised by
 * real code and tests, and so that Stage 1–3 can wire the pipeline against a working engine.
 * Stage 4 will replace the scoring internals (not the interface) with trajectory-aware logic
 * and calibration hooks.
 *
 * <p>Scoring (all numbers from {@link RiskConfig}):
 * <ul>
 *   <li>driverRisk: max of per-signal weights for closed eyes / prolonged closure / PERCLOS / yawning.</li>
 *   <li>roadRisk: max over tracks of classWeight × confidence × positionFactor.</li>
 *   <li>collisionRisk: from available TTC/distance estimates and closing/approaching movement.</li>
 *   <li>totalRisk: weighted combination, clamped to [0,1]; driver + road combined synergistically.</li>
 * </ul>
 */
public final class BaselineRiskEngine implements RiskEngine {

    private final RiskConfig config;

    public BaselineRiskEngine(RiskConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public RiskAssessment evaluate(RiskInput input) {
        Set<RiskReason> reasons = new LinkedHashSet<>();

        float driverRisk = evaluateDriver(input.driverState(), reasons);
        float roadRisk = evaluateRoad(input, reasons);
        float collisionRisk = evaluateCollision(input, reasons);

        if (!input.roadDetectorOk()) {
            reasons.add(RiskReason.ROAD_DETECTOR_UNAVAILABLE);
        }
        if (input.vehicle().speedAvailable() && input.vehicle().speedMps() >= config.highSpeedMps()) {
            reasons.add(RiskReason.HIGH_VEHICLE_SPEED);
        }

        float total = combine(driverRisk, roadRisk, collisionRisk, input);
        RiskLevel level = config.levelFor(total);

        // A level above NORMAL must always be explainable. If the configuration produced a level
        // without a specific reason (should not happen), fall back to the most generic true reason.
        if (level != RiskLevel.NORMAL && reasons.isEmpty()) {
            reasons.add(RiskReason.LOW_CONFIDENCE_ONLY);
        }
        // Reasons for NORMAL are allowed (e.g. DRIVER_STATE_UNAVAILABLE) but keep them informative only.

        return new RiskAssessment(driverRisk, roadRisk, collisionRisk, total, level,
                new ArrayList<>(reasons), input.timestampNanos());
    }

    private float evaluateDriver(DriverState d, Set<RiskReason> reasons) {
        if (!d.faceDetected() && d.confidence() == 0f && !d.perclosAvailable()) {
            reasons.add(RiskReason.DRIVER_STATE_UNAVAILABLE);
            return 0f;
        }
        float risk = 0f;
        if (!d.faceDetected()) {
            reasons.add(RiskReason.DRIVER_FACE_NOT_DETECTED);
            risk = Math.max(risk, config.driverWeights().faceNotDetected());
        }
        if (d.eyesClosed()) {
            reasons.add(RiskReason.DRIVER_EYES_CLOSED);
            risk = Math.max(risk, config.driverWeights().eyesClosed());
            if (d.eyeClosureDurationMillis() >= config.driverGuard().prolongedEyeClosureMillis()) {
                reasons.add(RiskReason.DRIVER_PROLONGED_EYE_CLOSURE);
                risk = Math.max(risk, config.driverWeights().prolongedEyeClosure());
            }
        }
        if (d.perclosAvailable() && d.perclos() >= config.driverGuard().perclosWarningFraction()) {
            reasons.add(RiskReason.DRIVER_HIGH_PERCLOS);
            risk = Math.max(risk, config.driverWeights().highPerclos());
        }
        if (d.yawningDetected()) {
            reasons.add(RiskReason.DRIVER_YAWNING);
            risk = Math.max(risk, config.driverWeights().yawning());
        }
        return clamp(risk);
    }

    private float evaluateRoad(RiskInput input, Set<RiskReason> reasons) {
        float risk = 0f;
        int relevant = 0;
        for (TrackedObject t : input.tracks()) {
            if (t.confidence() < config.minConfidenceForRisk()) {
                continue;
            }
            relevant++;
            float classWeight = config.classWeight(t.objectClass());
            float position = t.inDrivingCorridor()
                    ? config.roadWeights().inCorridorFactor()
                    : config.roadWeights().outsideCorridorFactor();
            float objectRisk = classWeight * t.confidence() * position;

            // Large apparent size is a coarse proximity proxy that does not pretend to be distance.
            float frameArea = (float) input.frameWidth() * (float) input.frameHeight();
            if (frameArea > 0f && t.box().area() / frameArea >= config.roadWeights().largeObjectAreaFraction()) {
                reasons.add(RiskReason.OBJECT_LARGE_IN_FRAME);
                objectRisk = Math.max(objectRisk, classWeight * config.roadWeights().largeObjectFactor());
            }

            reasons.add(reasonFor(t.objectClass()));
            if (t.inDrivingCorridor()) {
                reasons.add(RiskReason.OBJECT_IN_DRIVING_CORRIDOR);
            }
            risk = Math.max(risk, objectRisk);
        }
        if (relevant >= config.roadWeights().multipleHazardsCount()) {
            reasons.add(RiskReason.MULTIPLE_HAZARDS_DETECTED);
            risk = Math.min(1f, risk + config.roadWeights().multipleHazardsBonus());
        }
        if (relevant == 0 && !input.tracks().isEmpty()) {
            reasons.add(RiskReason.LOW_CONFIDENCE_ONLY);
        }
        return clamp(risk);
    }

    private float evaluateCollision(RiskInput input, Set<RiskReason> reasons) {
        float risk = 0f;
        for (TrackedObject t : input.tracks()) {
            if (t.confidence() < config.minConfidenceForRisk()) {
                continue;
            }
            if (t.movement() == MovementClass.APPROACHING_CORRIDOR) {
                reasons.add(RiskReason.OBJECT_APPROACHING_DRIVING_CORRIDOR);
                risk = Math.max(risk, config.collisionWeights().approachingCorridor());
            }
            if (t.movement() == MovementClass.CLOSING) {
                reasons.add(RiskReason.OBJECT_CLOSING);
                risk = Math.max(risk, config.collisionWeights().closing());
            }
            if (t.estimatedTtc().available() && t.estimatedTtc().value() <= config.collisionWeights().lowTtcSeconds()) {
                reasons.add(RiskReason.LOW_ESTIMATED_TTC);
                risk = Math.max(risk, config.collisionWeights().lowTtc());
            }
            if (t.estimatedDistance().available()
                    && t.estimatedDistance().value() <= config.collisionWeights().lowDistanceMeters()) {
                reasons.add(RiskReason.LOW_ESTIMATED_DISTANCE);
                risk = Math.max(risk, config.collisionWeights().lowDistance());
            }
        }
        return clamp(risk);
    }

    private float combine(float driver, float road, float collision, RiskInput input) {
        RiskConfig.TotalWeights w = config.totalWeights();
        float base = w.driver() * driver + w.road() * road + w.collision() * collision;
        // Synergy: a drowsy driver AND a hazard is worse than either alone.
        float synergy = w.driverRoadSynergy() * driver * Math.max(road, collision);
        float speedBoost = 0f;
        if (input.vehicle().speedAvailable() && input.vehicle().speedMps() >= config.highSpeedMps()) {
            speedBoost = w.highSpeedBoost() * Math.max(road, collision);
        }
        return clamp(base + synergy + speedBoost);
    }

    private static RiskReason reasonFor(ObjectClass c) {
        switch (c) {
            case PERSON: return RiskReason.PERSON_DETECTED;
            case HORSE: return RiskReason.HORSE_DETECTED;
            case COW: return RiskReason.COW_DETECTED;
            case SHEEP: return RiskReason.SHEEP_DETECTED;
            case GOAT: return RiskReason.GOAT_DETECTED;
            case CAMEL: return RiskReason.CAMEL_DETECTED;
            case DOG: return RiskReason.DOG_DETECTED;
            case UNKNOWN:
            default:
                return RiskReason.UNKNOWN_OBJECT_DETECTED;
        }
    }

    private static float clamp(float v) {
        if (Float.isNaN(v)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, v));
    }
}
