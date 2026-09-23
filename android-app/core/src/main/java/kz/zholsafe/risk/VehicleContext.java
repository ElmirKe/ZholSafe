package kz.zholsafe.risk;

import kz.zholsafe.model.Contracts;

/**
 * Optional ego-vehicle context available to the Risk Engine. Every field has an availability
 * flag; the engine must degrade gracefully when nothing is available (e.g. GPS off).
 * When {@code speedAvailable} the speed must be finite and non-negative; when not available it
 * must be the {@code Float.NaN} sentinel.
 *
 * @param speedAvailable whether {@code speedMps} is valid
 * @param speedMps       ground speed in m/s (from GNSS or vehicle bus)
 * @param nightMode      whether low-light conditions were detected/declared
 */
public record VehicleContext(boolean speedAvailable, float speedMps, boolean nightMode) {

    public VehicleContext {
        if (speedAvailable) {
            Contracts.nonNegative("speedMps", speedMps);
        } else if (!Float.isNaN(speedMps)) {
            throw new IllegalArgumentException("speedMps must be NaN when speedAvailable == false, got " + speedMps);
        }
    }

    public static final VehicleContext UNKNOWN = new VehicleContext(false, Float.NaN, false);

    public static VehicleContext withSpeed(float speedMps) {
        return new VehicleContext(true, speedMps, false);
    }
}
