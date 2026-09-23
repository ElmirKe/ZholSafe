package kz.zholsafe.risk;

import java.util.List;
import java.util.Objects;

/**
 * DATA CONTRACT: explainable output of the Risk Engine for one evaluation cycle.
 *
 * <p>All risk components are in [0,1]. {@code level} is derived from {@code totalRisk} using
 * thresholds in {@link kz.zholsafe.config.RiskConfig}. {@code reasons} is never null and must be
 * non-empty whenever {@code level != NORMAL}.
 *
 * @param driverRisk     contribution from DriverGuard
 * @param roadRisk       contribution from hazard presence (class, confidence, position)
 * @param collisionRisk  contribution from trajectory / distance / TTC
 * @param totalRisk      combined risk in [0,1]
 * @param level          discretised severity
 * @param reasons        ordered, de-duplicated explanation codes
 * @param timestampNanos timestamp of the evaluation
 */
public record RiskAssessment(
        float driverRisk,
        float roadRisk,
        float collisionRisk,
        float totalRisk,
        RiskLevel level,
        List<RiskReason> reasons,
        long timestampNanos) {

    public RiskAssessment {
        Objects.requireNonNull(level, "level");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        checkUnit("driverRisk", driverRisk);
        checkUnit("roadRisk", roadRisk);
        checkUnit("collisionRisk", collisionRisk);
        checkUnit("totalRisk", totalRisk);
        if (level != RiskLevel.NORMAL && reasons.isEmpty()) {
            throw new IllegalArgumentException("RiskAssessment with level " + level + " must have reasons");
        }
    }

    private static void checkUnit(String name, float v) {
        if (Float.isNaN(v) || v < 0f || v > 1f) {
            throw new IllegalArgumentException(name + " must be in [0,1], got " + v);
        }
    }

    /** Baseline assessment when nothing is known and nothing is detected. */
    public static RiskAssessment normal(long timestampNanos) {
        return new RiskAssessment(0f, 0f, 0f, 0f, RiskLevel.NORMAL, List.of(), timestampNanos);
    }
}
