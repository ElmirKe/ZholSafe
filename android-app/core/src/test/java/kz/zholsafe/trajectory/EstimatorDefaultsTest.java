package kz.zholsafe.trajectory;

import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Estimate;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.risk.VehicleContext;
import kz.zholsafe.tracking.MovementClass;
import kz.zholsafe.tracking.TrackedObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class EstimatorDefaultsTest {

    @Test
    void unavailableEstimatorsNeverFabricateValues() {
        BoundingBox b = new BoundingBox(0, 0, 100, 200);
        TrackedObject t = new TrackedObject(1, ObjectClass.COW, 0.9f, b, List.of(b.center()),
                MovementClass.UNKNOWN, Estimate.unavailable(), Estimate.unavailable(), true, 1, 0L);
        assertFalse(DistanceEstimator.UNAVAILABLE.estimateDistanceMeters(t, 640, 480).available());
        assertFalse(TtcEstimator.UNAVAILABLE.estimateTtcSeconds(t, VehicleContext.UNKNOWN).available());
        assertFalse(t.distanceEstimated());
        assertFalse(t.ttcEstimated());
    }
}
