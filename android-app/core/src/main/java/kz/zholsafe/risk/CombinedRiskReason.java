package kz.zholsafe.risk;

/**
 * Machine-readable explanation codes for {@link CombinedRiskSnapshot}.
 *
 * <p>Any escalation ABOVE the maximum of the two component levels MUST carry
 * {@link #COMBINED_HAZARD_ESCALATION} — escalations are never silent (enforced by the
 * {@link CombinedRiskSnapshot} record). Freshness/degraded paths carry explicit codes so a stale
 * WARNING/CRITICAL can never influence the fused level invisibly.
 */
public enum CombinedRiskReason {
    /** Road component at CAUTION or above contributed to the combined level. */
    ROAD_HAZARD_PRESENT,
    /** Driver component at CAUTION or above contributed to the combined level. */
    DRIVER_RISK_PRESENT,
    /** Both components at WARNING or above ⇒ CRITICAL — the explicit interaction escalation reason. */
    COMBINED_HAZARD_ESCALATION,
    /** Driver impairment evidence co-occurs with a road hazard (informative interaction context). */
    DRIVER_IMPAIRMENT_WITH_ROAD_HAZARD,
    /** Road snapshot too old vs the source-time reference — excluded from fusion. */
    STALE_ROAD_STATE,
    /** Driver snapshot too old vs the source-time reference — excluded from fusion. */
    STALE_DRIVER_STATE,
    /** Road/driver source-time skew exceeded the configured maximum — single-source fallback. */
    ROAD_DRIVER_TIMESTAMP_SKEW,
    /** Road risk unavailable at the source. */
    ROAD_UNAVAILABLE,
    /** Driver risk unavailable at the source. */
    DRIVER_UNAVAILABLE
}
