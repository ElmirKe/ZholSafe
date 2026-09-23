package kz.zholsafe.risk;

import kz.zholsafe.driver.DriverState;
import kz.zholsafe.driver.EyeState;
import kz.zholsafe.driver.HeadPose;
import kz.zholsafe.driver.HeadPoseState;
import kz.zholsafe.driver.ObservationQuality;
import kz.zholsafe.driver.PerclosValue;
import kz.zholsafe.driver.YawnLikeState;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.physical.EvidenceQuality;
import kz.zholsafe.physical.PhysicalEstimationSnapshot;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Builders for fusion tests: genuine {@link RoadRiskSnapshot}/{@link DriverRiskSnapshot} values
 * going through their real record validation — test DATA, never a stubbed engine.
 */
public final class CombinedRiskTestSupport {

    public static final long TS = 10_000_000_000L; // arbitrary positive source time

    private CombinedRiskTestSupport() { }

    /** READY road snapshot at the given level (NORMAL = observed empty road, no objects). */
    public static RoadRiskSnapshot road(long ts, RiskLevel level) {
        if (level == RiskLevel.NORMAL) {
            return new RoadRiskSnapshot(ts, 1280, 720, RoadRiskSnapshot.Status.READY,
                    TrackingSnapshot.Status.READY, TrajectorySnapshot.Status.READY,
                    PhysicalEstimationSnapshot.Status.READY, List.of(),
                    Optional.of(RiskLevel.NORMAL), OptionalInt.empty());
        }
        double score = switch (level) {
            case CAUTION -> 0.25d;
            case WARNING -> 0.60d;
            default -> 0.90d;
        };
        RiskReason reason = level == RiskLevel.CAUTION
                ? RiskReason.OBJECT_NEAR_DRIVING_CORRIDOR : RiskReason.OBJECT_IN_DRIVING_CORRIDOR;
        RiskEvidenceType evidenceType = level == RiskLevel.CAUTION
                ? RiskEvidenceType.OBJECT_NEAR_CORRIDOR : RiskEvidenceType.OBJECT_IN_CORRIDOR;
        RiskComponents components = new RiskComponents(score, 0d, 0d, 0d, 0d, 0d);
        ObjectRiskAssessment assessment = new ObjectRiskAssessment(1, ObjectClass.PERSON, ts, level,
                components.cappedTotal(), EvidenceQuality.MEDIUM, components, List.of(reason),
                List.of(RiskEvidence.flag(evidenceType, EvidenceSource.TRACKING, EvidenceQuality.MEDIUM)));
        return new RoadRiskSnapshot(ts, 1280, 720, RoadRiskSnapshot.Status.READY,
                TrackingSnapshot.Status.READY, TrajectorySnapshot.Status.READY,
                PhysicalEstimationSnapshot.Status.READY, List.of(assessment),
                Optional.of(level), OptionalInt.of(1));
    }

    /** Road pipeline never produced anything (NOT_STARTED degradation placeholder). */
    public static RoadRiskSnapshot roadNotStarted() {
        return RoadRiskSnapshot.unavailable(0L, RoadRiskSnapshot.Status.NOT_STARTED,
                TrackingSnapshot.Status.NOT_STARTED, TrajectorySnapshot.Status.NOT_STARTED,
                PhysicalEstimationSnapshot.Status.NOT_STARTED);
    }

    /**
     * READY driver snapshot at the requested level, produced by the REAL {@link DriverRiskEngine}
     * from a fabricated coherent {@link DriverState} (closure-driven severity; PERCLOS unavailable).
     */
    public static DriverRiskSnapshot driver(DriverRiskEvaluator engine, long ts, RiskLevel level) {
        long closure = switch (level) {
            case CAUTION -> 800_000_000L;
            case WARNING -> 1_600_000_000L;
            case CRITICAL -> 3_200_000_000L;
            default -> 0L;
        };
        DriverState state = new DriverState(ts, true,
                level == RiskLevel.NORMAL ? EyeState.OPEN : EyeState.CLOSED, closure,
                PerclosValue.unavailable(0L, 0L), HeadPoseState.UNKNOWN, 0L, HeadPose.UNAVAILABLE,
                YawnLikeState.UNAVAILABLE, 0L, 0L, 0L, ObservationQuality.GOOD, 0.9f,
                DriverState.TimestampRejection.NONE);
        return engine.evaluate(state);
    }
}
