package kz.zholsafe.driver;

/**
 * Engineering grade of the latest accepted driver observation — a monitoring-quality signal,
 * NOT a correctness probability and NOT evidence about the driver's condition.
 */
public enum ObservationQuality {
    /** Face visible, eyes evaluable, confidence above the configured minimum. */
    GOOD,
    /** Face visible but eyes not evaluable, or confidence below the configured minimum. */
    DEGRADED,
    /** No usable face observation at all. */
    UNAVAILABLE
}
