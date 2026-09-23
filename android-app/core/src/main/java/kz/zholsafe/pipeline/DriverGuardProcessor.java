package kz.zholsafe.pipeline;

import kz.zholsafe.ai.DetectionException;
import kz.zholsafe.ai.Frame;
import kz.zholsafe.config.DriverGuardConfig;
import kz.zholsafe.driver.DriverObservation;
import kz.zholsafe.driver.DriverObservationProvider;
import kz.zholsafe.driver.DriverState;
import kz.zholsafe.driver.DriverStateAnalyzer;
import kz.zholsafe.driver.TemporalDriverStateAnalyzer;
import kz.zholsafe.risk.DriverRiskEngine;
import kz.zholsafe.risk.DriverRiskEvaluator;
import kz.zholsafe.risk.DriverRiskSnapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stage 4.3 driver-facing {@link FrameProcessor}: {@code Frame → DriverObservationProvider →
 * DriverStateAnalyzer → DriverRiskEvaluator}; publishes the latest immutable {@link DriverState}
 * and {@link DriverRiskSnapshot}.
 *
 * <p>Kept deliberately OUT of {@link RoadDetectionProcessor}: road and driver pipelines stay
 * modular. This processor is wired to its OWN {@link FramePipeline} + {@link LatestFrameQueue}
 * (bounded, latest-frame, drop-oldest; capacity exactly one), so driver processing can never
 * block road processing and no queue can grow unboundedly. {@code frame.data()} is never touched
 * beyond the provider call and never retained — the analyzer keeps scalars only.
 *
 * <p>Failure handling mirrors {@link RoadDetectionProcessor}: a provider/evaluator exception
 * publishes an UNAVAILABLE snapshot (never a fabricated NORMAL) and is rethrown so
 * {@link FramePipeline} counts it in telemetry.
 */
public final class DriverGuardProcessor implements FrameProcessor {

    private final DriverObservationProvider provider;
    private final DriverStateAnalyzer analyzer;
    private final DriverRiskEvaluator riskEvaluator;
    private final AtomicReference<DriverState> latestState;
    private final AtomicReference<DriverRiskSnapshot> latestRisk;

    public DriverGuardProcessor(DriverObservationProvider provider, DriverGuardConfig config) {
        this(provider, new TemporalDriverStateAnalyzer(Objects.requireNonNull(config, "config")),
                new DriverRiskEngine(config));
    }

    /** Fully injectable for JVM tests and future analyzers/evaluators. */
    public DriverGuardProcessor(DriverObservationProvider provider, DriverStateAnalyzer analyzer,
                                DriverRiskEvaluator riskEvaluator) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.riskEvaluator = Objects.requireNonNull(riskEvaluator, "riskEvaluator");
        this.latestState = new AtomicReference<>(DriverState.unavailable(0L));
        this.latestRisk = new AtomicReference<>(DriverRiskSnapshot.notStarted());
    }

    @Override
    public void process(Frame frame) throws DetectionException {
        Objects.requireNonNull(frame, "frame");
        DriverObservation observation;
        try {
            observation = provider.provide(frame);
        } catch (DetectionException | RuntimeException e) {
            latestState.set(DriverState.unavailable(frame.timestampNanos()));
            latestRisk.set(DriverRiskSnapshot.unavailable(frame.timestampNanos()));
            throw e;
        }
        processObservation(observation);
    }

    /**
     * Consumes one pre-built observation (replay/demo/test entry without a camera frame).
     * Same production analyzer + risk chain as {@link #process(Frame)}.
     */
    public DriverRiskSnapshot processObservation(DriverObservation observation) {
        Objects.requireNonNull(observation, "observation");
        DriverState state = analyzer.update(observation);
        DriverRiskSnapshot risk;
        try {
            risk = riskEvaluator.evaluate(state);
        } catch (RuntimeException e) {
            latestState.set(state);
            latestRisk.set(DriverRiskSnapshot.unavailable(observation.timestampNanos()));
            throw e;
        }
        latestState.set(state);
        latestRisk.set(risk);
        return risk;
    }

    public DriverState latestState() {
        return latestState.get();
    }

    public DriverRiskSnapshot latestRisk() {
        return latestRisk.get();
    }

    /** Resets temporal memory; snapshots become NOT_STARTED again. */
    public void reset() {
        analyzer.reset();
        latestState.set(DriverState.unavailable(0L));
        latestRisk.set(DriverRiskSnapshot.notStarted());
    }

    @Override
    public String statusLine() {
        return "DriverGuard[" + provider.sourceId() + "] " + latestRisk.get().status();
    }
}
