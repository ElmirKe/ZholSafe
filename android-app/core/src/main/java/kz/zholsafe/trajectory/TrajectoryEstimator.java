package kz.zholsafe.trajectory;

import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;
import kz.zholsafe.tracking.MovementClass;
import kz.zholsafe.tracking.TrackedObject;

/**
 * Pure-Java image-space trajectory port. Stage 4.0 consumes immutable Stage 3 tracking metadata;
 * no Android, camera buffer, physical trajectory or Risk Engine dependency. Implementations are
 * stateless: a bounded window of source-timestamped observations is supplied on every call.
 */
public interface TrajectoryEstimator {

    TrajectorySnapshot estimate(TrackingSnapshot tracking);

    /**
     * Stage 0 placeholder retained for source compatibility. A TrackedObject's centre list alone
     * has no timestamps or per-sample boxes, so it CANNOT support a truthful motion classification.
     * Always UNKNOWN. Consumers must use {@link #estimate(TrackingSnapshot)} instead; mapping
     * image growth to MovementClass.CLOSING would falsely feed physical Risk Engine logic.
     */
    @Deprecated
    default MovementClass classify(TrackedObject object, int frameWidth, int frameHeight) {
        return MovementClass.UNKNOWN;
    }
}
