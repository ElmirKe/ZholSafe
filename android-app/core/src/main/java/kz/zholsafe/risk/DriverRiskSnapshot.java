package kz.zholsafe.risk;

import kz.zholsafe.driver.DriverState;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable Stage 4.3 DRIVER-ONLY severity snapshot produced by {@link DriverRiskEvaluator}.
 *
 * <p>{@link #level()} is an ENGINEERING severity classification — it is NOT a probability of
 * falling asleep and NOT a medical statement. The evaluated {@link DriverState} is preserved so
 * the level can always be traced back to explicit temporal evidence.
 *
 * <p>Invariants (constructor-enforced): READY ⇒ level present, shared source timestamp with the
 * evaluated state, and every non-NORMAL level carries at least one structured reason (NORMAL may
 * carry informative reasons); NOT_STARTED / UNAVAILABLE ⇒ no level and no reasons — a monitoring
 * failure is never silently reported as NORMAL.
 */
public record DriverRiskSnapshot(
        long timestampNanos,
        Status status,
        Optional<RiskLevel> level,
        List<DriverRiskReason> reasons,
        DriverState driverState) {

    public enum Status {
        /** A driver state was evaluated; level and reasons are meaningful. */
        READY,
        /** DriverGuard pipeline created but no observation processed yet. */
        NOT_STARTED,
        /** Provider/evaluator failed — driver monitoring unavailable. */
        UNAVAILABLE
    }

    public DriverRiskSnapshot {
        if (timestampNanos < 0L) {
            throw new IllegalArgumentException("negative source time");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(level, "level");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        Objects.requireNonNull(driverState, "driverState");
        if (status == Status.READY) {
            if (level.isEmpty()) {
                throw new IllegalArgumentException("READY requires a level");
            }
            if (level.get() != RiskLevel.NORMAL && reasons.isEmpty()) {
                throw new IllegalArgumentException("non-NORMAL driver risk requires structured reasons");
            }
            if (driverState.timestampNanos() != timestampNanos) {
                throw new IllegalArgumentException("driver risk must share the state's source timestamp");
            }
        } else if (level.isPresent() || !reasons.isEmpty()) {
            throw new IllegalArgumentException("unavailable driver risk cannot publish a level");
        }
    }

    /** Pre-first-observation placeholder. */
    public static DriverRiskSnapshot notStarted() {
        return new DriverRiskSnapshot(0L, Status.NOT_STARTED, Optional.empty(), List.of(),
                DriverState.unavailable(0L));
    }

    /** Monitoring failure placeholder — explicitly without a level, not a fabricated NORMAL. */
    public static DriverRiskSnapshot unavailable(long timestampNanos) {
        return new DriverRiskSnapshot(timestampNanos, Status.UNAVAILABLE, Optional.empty(), List.of(),
                DriverState.unavailable(timestampNanos));
    }

    /** Whether this snapshot carries a usable evaluated level. */
    public boolean available() {
        return status == Status.READY;
    }
}
