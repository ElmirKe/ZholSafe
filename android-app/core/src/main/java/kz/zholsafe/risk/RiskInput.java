package kz.zholsafe.risk;

import kz.zholsafe.driver.DriverState;
import kz.zholsafe.tracking.TrackedObject;

import java.util.List;
import java.util.Objects;

/**
 * Everything the Risk Engine is allowed to look at in one evaluation. Deliberately a plain value
 * so that {@link RiskEngine} is unit-testable with no camera, network, database or model.
 *
 * @param tracks          current tracked road objects (may be empty)
 * @param driverState     current driver state ({@link DriverState#unavailable} if DriverGuard off)
 * @param vehicle         ego-vehicle context ({@link VehicleContext#UNKNOWN} if none)
 * @param roadDetectorOk  whether RoadGuard is operational (false ⇒ reason ROAD_DETECTOR_UNAVAILABLE)
 * @param frameWidth      width of the road frame the tracks refer to
 * @param frameHeight     height of the road frame the tracks refer to
 * @param timestampNanos  evaluation timestamp
 */
public record RiskInput(
        List<TrackedObject> tracks,
        DriverState driverState,
        VehicleContext vehicle,
        boolean roadDetectorOk,
        int frameWidth,
        int frameHeight,
        long timestampNanos) {

    public RiskInput {
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        Objects.requireNonNull(driverState, "driverState");
        Objects.requireNonNull(vehicle, "vehicle");
    }
}
