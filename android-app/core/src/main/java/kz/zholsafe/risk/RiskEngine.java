package kz.zholsafe.risk;

/**
 * Core original ZholSafe component: fuses independent signals into an explainable
 * {@link RiskAssessment}.
 *
 * <pre>
 *   driver risk + road hazard risk + trajectory risk + collision risk + vehicle context
 *   = total risk  →  RiskLevel + reasons
 * </pre>
 *
 * <p>Contract rules (enforced by {@code RiskEngineContractTest}):
 * <ul>
 *   <li>Pure function of {@link RiskInput} and {@link kz.zholsafe.config.RiskConfig}; no I/O.</li>
 *   <li>Deterministic for the same input (demo mode must reproduce live-mode decisions).</li>
 *   <li>Never returns a non-NORMAL level without reasons.</li>
 *   <li>Detection ≠ risk: a high-confidence hazard far from the corridor is not CRITICAL on its own.</li>
 *   <li>All weights/thresholds come from configuration, never literals in the engine.</li>
 * </ul>
 * Stage 0's scalar/combined-driver contract remains source-compatible. Stage 4.2 evaluates
 * source-aligned ROAD snapshots through {@link RoadRiskEvaluator} and reuses RiskLevel/RiskReason;
 * it cannot safely reinterpret RiskInput's timestamp-free scalar tracked values as Stage 4.1
 * physical evidence. The legacy baseline remains for existing callers/tests; it is NOT the
 * road-only diagnostic path and does not authorize DriverGuard or production alerts.
 */
public interface RiskEngine {

    RiskAssessment evaluate(RiskInput input);
}
