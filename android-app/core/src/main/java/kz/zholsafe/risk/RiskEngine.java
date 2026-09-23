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
 * The full algorithm is implemented in Stage 4; Stage 0 ships the contract and a minimal
 * baseline implementation ({@link BaselineRiskEngine}) so that the pipeline can be wired and
 * tested end-to-end.
 */
public interface RiskEngine {

    RiskAssessment evaluate(RiskInput input);
}
