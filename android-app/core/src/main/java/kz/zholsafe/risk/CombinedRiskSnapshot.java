package kz.zholsafe.risk;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable Stage 4.3 road/driver fusion output from {@link CombinedRiskEvaluator}.
 *
 * <p>Both component snapshots are preserved untouched together with the fused level and structured
 * reasons — component information is never erased. Levels are ENGINEERING severity, never
 * collision or injury probabilities.
 *
 * <p>Statuses: {@link Status#READY} (both components fresh, fused through the deterministic rule
 * matrix), {@link Status#ROAD_ONLY} / {@link Status#DRIVER_ONLY} (the other source is unavailable,
 * stale or beyond the timestamp-skew budget — one valid subsystem stays usable), and
 * {@link Status#UNAVAILABLE} (both unusable ⇒ NO level — degraded is never NORMAL).
 *
 * <p>Invariants (constructor-enforced): non-UNAVAILABLE ⇒ combined level present; ROAD_ONLY ⇒
 * combined equals the road level; DRIVER_ONLY ⇒ combined equals the driver level; READY ⇒ both
 * component levels present; UNAVAILABLE ⇒ no combined level and a non-empty explanation; every
 * non-NORMAL combined level carries reasons; any combined level above max(road, driver) carries
 * {@link CombinedRiskReason#COMBINED_HAZARD_ESCALATION}.
 */
public record CombinedRiskSnapshot(
        long evaluationTimestampNanos,
        Status status,
        Optional<RiskLevel> roadLevel,
        Optional<RiskLevel> driverLevel,
        Optional<RiskLevel> combinedLevel,
        long roadTimestampNanos,
        long driverTimestampNanos,
        List<CombinedRiskReason> reasons,
        RoadRiskSnapshot roadRisk,
        DriverRiskSnapshot driverRisk) {

    public enum Status {
        /** Both components fresh and fused through the rule matrix. */
        READY,
        /** Only the road component usable; preserved as-is. */
        ROAD_ONLY,
        /** Only the driver component usable; preserved as-is. */
        DRIVER_ONLY,
        /** Both components unusable — degraded, explicitly without a level. */
        UNAVAILABLE
    }

    public CombinedRiskSnapshot {
        if (evaluationTimestampNanos < 0L || roadTimestampNanos < 0L || driverTimestampNanos < 0L) {
            throw new IllegalArgumentException("negative source time");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(roadLevel, "roadLevel");
        Objects.requireNonNull(driverLevel, "driverLevel");
        Objects.requireNonNull(combinedLevel, "combinedLevel");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        Objects.requireNonNull(roadRisk, "roadRisk");
        Objects.requireNonNull(driverRisk, "driverRisk");
        if (status == Status.UNAVAILABLE) {
            if (combinedLevel.isPresent() || reasons.isEmpty()) {
                throw new IllegalArgumentException(
                        "unavailable fusion cannot publish a level and must explain the degradation");
            }
        } else {
            if (combinedLevel.isEmpty()) {
                throw new IllegalArgumentException("fused snapshot requires a combined level");
            }
            if (status == Status.ROAD_ONLY
                    && (roadLevel.isEmpty() || combinedLevel.get() != roadLevel.get())) {
                throw new IllegalArgumentException("ROAD_ONLY must preserve the road level");
            }
            if (status == Status.DRIVER_ONLY
                    && (driverLevel.isEmpty() || combinedLevel.get() != driverLevel.get())) {
                throw new IllegalArgumentException("DRIVER_ONLY must preserve the driver level");
            }
            if (status == Status.READY && (roadLevel.isEmpty() || driverLevel.isEmpty())) {
                throw new IllegalArgumentException("READY fusion requires both component levels");
            }
            if (combinedLevel.get() != RiskLevel.NORMAL && reasons.isEmpty()) {
                throw new IllegalArgumentException("non-NORMAL combined risk requires structured reasons");
            }
            if (roadLevel.isPresent() && driverLevel.isPresent()) {
                RiskLevel maxComponent = roadLevel.get().ordinal() >= driverLevel.get().ordinal()
                        ? roadLevel.get() : driverLevel.get();
                if (combinedLevel.get().ordinal() > maxComponent.ordinal()
                        && !reasons.contains(CombinedRiskReason.COMBINED_HAZARD_ESCALATION)) {
                    throw new IllegalArgumentException(
                            "escalation above max(road, driver) requires COMBINED_HAZARD_ESCALATION");
                }
            }
        }
    }

    /** Whether a real fusion of two fresh components happened. */
    public boolean fused() {
        return status == Status.READY;
    }

    /** Whether this snapshot carries a usable combined level. */
    public boolean available() {
        return status != Status.UNAVAILABLE;
    }
}
