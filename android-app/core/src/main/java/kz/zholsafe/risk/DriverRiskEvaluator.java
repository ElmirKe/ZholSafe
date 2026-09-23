package kz.zholsafe.risk;

import kz.zholsafe.driver.DriverState;

/**
 * Stage 4.3 driver-only risk port: one immutable {@link DriverState} → one immutable
 * {@link DriverRiskSnapshot}. Implementations must be deterministic and must not look at any road
 * data (object classes, tracks, TTC, corridor) — the driver pipeline is standalone.
 */
public interface DriverRiskEvaluator {

    DriverRiskSnapshot evaluate(DriverState state);
}
