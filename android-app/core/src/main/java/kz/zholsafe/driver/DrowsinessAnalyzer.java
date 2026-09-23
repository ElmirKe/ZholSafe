package kz.zholsafe.driver;

/**
 * Turns a stream of {@link DriverObservation}s into a {@link DriverState}.
 *
 * <p>Pure Java, stateful, single-threaded. Thresholds (eye-closure duration, PERCLOS window)
 * come from {@link kz.zholsafe.config.DriverGuardConfig} and are EXPERIMENTAL DEMO VALUES,
 * not validated medical or regulatory thresholds. Deferred Stage 4.3 provides the implementation.
 */
public interface DrowsinessAnalyzer {

    DriverState update(DriverObservation observation);

    DriverState current();

    void reset();
}
