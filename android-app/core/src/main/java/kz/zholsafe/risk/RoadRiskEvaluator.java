package kz.zholsafe.risk;

import kz.zholsafe.physical.PhysicalEstimationSnapshot;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;

/** Stage 4.2 snapshot-specific road-only RiskEngine port; legacy scalar RiskEngine remains intact. */
public interface RoadRiskEvaluator {
    RoadRiskSnapshot evaluate(TrackingSnapshot tracking, TrajectorySnapshot trajectory,
                              PhysicalEstimationSnapshot physical);
}
