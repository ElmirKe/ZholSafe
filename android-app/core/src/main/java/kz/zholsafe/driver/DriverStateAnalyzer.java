package kz.zholsafe.driver;

/**
 * Turns a stream of {@link DriverObservation}s into a {@link DriverState} using only DRIVER
 * SOURCE TIMESTAMPS ({@link DriverObservation#timestampNanos()}) — never frame counts and never
 * the processing clock. Pure Java, stateful, single-threaded, bounded memory (no frames/images).
 *
 * <p>Implemented by {@link TemporalDriverStateAnalyzer}. Replaces the Stage 0
 * {@code DrowsinessAnalyzer} placeholder interface of deferred Stage 4.3; the "drowsiness" naming
 * was dropped on purpose: this contract measures temporal evidence, it does not diagnose.
 * All thresholds come from {@link kz.zholsafe.config.DriverGuardConfig} and are EXPERIMENTAL demo
 * values, not medical or regulatory thresholds.
 */
public interface DriverStateAnalyzer {

    /** Accepts or explicitly rejects one observation and returns the updated immutable state. */
    DriverState update(DriverObservation observation);

    /** Latest state without consuming a new observation. */
    DriverState current();

    /** Drops all temporal memory. */
    void reset();
}
