package kz.zholsafe.config;

import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.risk.RiskLevel;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Centralised Risk Engine weights and thresholds.
 *
 * <p>All values are EXPERIMENTAL defaults for the Stage 0 baseline; they are the single place to
 * tune, and are expected to be calibrated from empirical data in later stages. No risk-related
 * literal may appear in engine code.
 */
public record RiskConfig(
        Map<ObjectClass, Float> classWeights,
        DriverWeights driverWeights,
        RoadWeights roadWeights,
        CollisionWeights collisionWeights,
        TotalWeights totalWeights,
        LevelThresholds levelThresholds,
        DriverGuardConfig driverGuard,
        float minConfidenceForRisk,
        float highSpeedMps) {

    public RiskConfig {
        Objects.requireNonNull(classWeights, "classWeights");
        Objects.requireNonNull(driverWeights, "driverWeights");
        Objects.requireNonNull(roadWeights, "roadWeights");
        Objects.requireNonNull(collisionWeights, "collisionWeights");
        Objects.requireNonNull(totalWeights, "totalWeights");
        Objects.requireNonNull(levelThresholds, "levelThresholds");
        Objects.requireNonNull(driverGuard, "driverGuard");
        classWeights = Map.copyOf(classWeights);
    }

    /** Weight of each hazard class in [0,1]; unknown classes get {@code UNKNOWN}'s weight. */
    public float classWeight(ObjectClass c) {
        Float w = classWeights.get(c);
        if (w == null) {
            w = classWeights.getOrDefault(ObjectClass.UNKNOWN, 0.5f);
        }
        return w;
    }

    public RiskLevel levelFor(float totalRisk) {
        return levelThresholds.levelFor(totalRisk);
    }

    public record DriverWeights(
            float faceNotDetected,
            float eyesClosed,
            float prolongedEyeClosure,
            float highPerclos,
            float yawning) { }

    public record RoadWeights(
            float inCorridorFactor,
            float outsideCorridorFactor,
            float largeObjectAreaFraction,
            float largeObjectFactor,
            int multipleHazardsCount,
            float multipleHazardsBonus) { }

    public record CollisionWeights(
            float approachingCorridor,
            float closing,
            double lowTtcSeconds,
            float lowTtc,
            double lowDistanceMeters,
            float lowDistance) { }

    public record TotalWeights(
            float driver,
            float road,
            float collision,
            float driverRoadSynergy,
            float highSpeedBoost) { }

    /** Monotonic thresholds: caution &lt;= warning &lt;= critical. */
    public record LevelThresholds(float caution, float warning, float critical) {
        public LevelThresholds {
            if (!(caution <= warning && warning <= critical)) {
                throw new IllegalArgumentException("thresholds must be monotonic");
            }
        }

        public RiskLevel levelFor(float total) {
            if (total >= critical) return RiskLevel.CRITICAL;
            if (total >= warning) return RiskLevel.WARNING;
            if (total >= caution) return RiskLevel.CAUTION;
            return RiskLevel.NORMAL;
        }
    }

    /** Experimental Stage 0 defaults. Tune here, not in code. */
    public static RiskConfig defaults() {
        Map<ObjectClass, Float> cw = new EnumMap<>(ObjectClass.class);
        cw.put(ObjectClass.PERSON, 1.00f);
        cw.put(ObjectClass.HORSE, 0.95f);
        cw.put(ObjectClass.COW, 0.95f);
        cw.put(ObjectClass.CAMEL, 0.95f);
        cw.put(ObjectClass.SHEEP, 0.70f);
        cw.put(ObjectClass.GOAT, 0.70f);
        cw.put(ObjectClass.DOG, 0.60f);
        cw.put(ObjectClass.UNKNOWN, 0.50f);
        return new RiskConfig(
                cw,
                new DriverWeights(0.20f, 0.50f, 0.90f, 0.70f, 0.30f),
                new RoadWeights(1.00f, 0.35f, 0.20f, 0.80f, 3, 0.10f),
                new CollisionWeights(0.60f, 0.70f, 2.5d, 0.95f, 15d, 0.85f),
                new TotalWeights(0.35f, 0.40f, 0.45f, 0.40f, 0.15f),
                new LevelThresholds(0.25f, 0.50f, 0.75f),
                DriverGuardConfig.defaults(),
                0.35f,
                22.0f); // ~80 km/h
    }
}
