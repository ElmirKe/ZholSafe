package kz.zholsafe.risk;

/**
 * Stage 4.3 fusion port: latest road + driver risk snapshots → one immutable
 * {@link CombinedRiskSnapshot}. Implementations must be deterministic, must not add component
 * scores, and must respect the freshness budgets of {@link kz.zholsafe.config.CombinedRiskConfig}.
 */
public interface CombinedRiskEvaluator {

    CombinedRiskSnapshot evaluate(RoadRiskSnapshot roadRisk, DriverRiskSnapshot driverRisk);
}
