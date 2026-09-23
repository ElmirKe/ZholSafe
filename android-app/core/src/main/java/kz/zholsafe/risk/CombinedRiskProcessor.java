package kz.zholsafe.risk;

import kz.zholsafe.config.CombinedRiskConfig;
import kz.zholsafe.physical.PhysicalEstimationSnapshot;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the latest road and driver risk snapshots — which arrive from two INDEPENDENT pipelines
 * with independent source clocks — and fuses them on demand through a {@link CombinedRiskEvaluator}.
 *
 * <p>Bounded by construction: exactly one snapshot per subsystem is retained; no queues, no frame
 * data, no buffering of history. Thread-safe for one producer per subsystem and any number of
 * readers.
 */
public final class CombinedRiskProcessor {

    private final CombinedRiskEvaluator evaluator;
    private final AtomicReference<RoadRiskSnapshot> latestRoad;
    private final AtomicReference<DriverRiskSnapshot> latestDriver;
    private final AtomicReference<CombinedRiskSnapshot> latestCombined;

    public CombinedRiskProcessor(CombinedRiskConfig config) {
        this(new CombinedRiskEngine(Objects.requireNonNull(config, "config")));
    }

    public CombinedRiskProcessor(CombinedRiskEvaluator evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.latestRoad = new AtomicReference<>(RoadRiskSnapshot.unavailable(0L,
                RoadRiskSnapshot.Status.NOT_STARTED,
                TrackingSnapshot.Status.NOT_STARTED,
                TrajectorySnapshot.Status.NOT_STARTED,
                PhysicalEstimationSnapshot.Status.NOT_STARTED));
        this.latestDriver = new AtomicReference<>(DriverRiskSnapshot.notStarted());
        this.latestCombined = new AtomicReference<>(
                this.evaluator.evaluate(this.latestRoad.get(), this.latestDriver.get()));
    }

    /** Publishes the newest road risk snapshot (replaces the previous one). */
    public void updateRoad(RoadRiskSnapshot road) {
        latestRoad.set(Objects.requireNonNull(road, "road"));
    }

    /** Publishes the newest driver risk snapshot (replaces the previous one). */
    public void updateDriver(DriverRiskSnapshot driver) {
        latestDriver.set(Objects.requireNonNull(driver, "driver"));
    }

    /** Fuses the latest snapshots and stores the result as the newest combined snapshot. */
    public CombinedRiskSnapshot evaluate() {
        CombinedRiskSnapshot combined = evaluator.evaluate(latestRoad.get(), latestDriver.get());
        latestCombined.set(combined);
        return combined;
    }

    public CombinedRiskSnapshot latest() {
        return latestCombined.get();
    }

    public RoadRiskSnapshot latestRoad() {
        return latestRoad.get();
    }

    public DriverRiskSnapshot latestDriver() {
        return latestDriver.get();
    }
}
