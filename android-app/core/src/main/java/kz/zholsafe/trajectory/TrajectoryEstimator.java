package kz.zholsafe.trajectory;

import kz.zholsafe.tracking.MovementClass;
import kz.zholsafe.tracking.TrackedObject;

/**
 * Classifies the movement of a track from its position/scale history and the configured
 * driving corridor. Stage 4 provides the implementation; it must be pure (no Android, no I/O).
 */
public interface TrajectoryEstimator {

    MovementClass classify(TrackedObject object, int frameWidth, int frameHeight);
}
