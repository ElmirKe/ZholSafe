package kz.zholsafe.pipeline;

import kz.zholsafe.risk.RiskAssessment;

/**
 * Local alert output port. Android implementation (Stage 1+) drives audio + visual alerts on
 * the UI thread. Must work with NO network, NO server, NO GPS.
 */
public interface AlertSink {

    /** Called whenever the RiskLevel changes or a CRITICAL assessment repeats. */
    void onRiskChanged(RiskAssessment previous, RiskAssessment current);
}
