package kz.zholsafe.pipeline;

import kz.zholsafe.ai.DetectionException;
import kz.zholsafe.ai.DetectorState;
import kz.zholsafe.ai.DetectorTimings;
import kz.zholsafe.ai.Frame;
import kz.zholsafe.ai.ModelNotAvailableException;
import kz.zholsafe.ai.RoadDetector;
import kz.zholsafe.config.TrackingConfig;
import kz.zholsafe.config.TrajectoryConfig;
import kz.zholsafe.config.RoadRiskConfig;
import kz.zholsafe.tracking.ByteTrackInspiredTracker;
import kz.zholsafe.trajectory.LinearImageTrajectoryEstimator;
import kz.zholsafe.trajectory.TrajectoryEstimator;
import kz.zholsafe.logging.ZLog;
import kz.zholsafe.physical.PhysicalEstimationProcessor;
import kz.zholsafe.physical.PhysicalEstimationSnapshot;
import kz.zholsafe.model.Detection;
import kz.zholsafe.risk.RoadRiskEngine;
import kz.zholsafe.risk.RoadRiskEvaluator;
import kz.zholsafe.risk.RoadRiskSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Stage 2–4.2 {@link FrameProcessor}: detector, tracker, image trajectory, physical diagnostics
 * and ROAD-ONLY risk on the SAME thread; publishes separate immutable snapshots. No alerts.
 *
 * <ul>
 *   <li>{@link #load()} is called by the owner before the pipeline starts (may be slow). If it
 *       fails the processor stays usable but every frame is reported as UNAVAILABLE and counted
 *       as a processing error — the pipeline goes DEGRADED, it never fabricates results.</li>
 *   <li>Per-frame failure: rethrown so {@link FramePipeline} counts/logs it; snapshot becomes
 *       unavailable. No empty "safe" list is published.</li>
 *   <li>Detector ERROR (fatal): same as above on every frame; the state is visible in the
 *       snapshot and the status line.</li>
 *   <li>{@code frame.data()} is never retained; detections carry only numbers.</li>
 * </ul>
 * Timing uses the injected monotonic clock. Only the latest snapshot is kept.
 */
public final class RoadDetectionProcessor implements FrameProcessor {

    private static final String TAG = "RoadDetection";

    private final RoadDetector detector;
    private final ByteTrackInspiredTracker tracker;
    private final TrajectoryEstimator trajectoryEstimator;
    private final PhysicalEstimationProcessor physicalEstimator;
    private final RoadRiskEvaluator roadRiskEvaluator;
    private final AtomicReference<RoadRiskSnapshot> riskLatest;
    private final AtomicReference<PhysicalEstimationSnapshot> physicalLatest;
    private final AtomicReference<TrackingSnapshot> trackingLatest;
    private final AtomicReference<TrajectorySnapshot> trajectoryLatest;
    private final LongSupplier clock;
    private final AtomicReference<DetectionSnapshot> latest;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong inferenceCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final PipelineTelemetry.FpsEstimator inferenceFps = new PipelineTelemetry.FpsEstimator();
    private volatile double emaTotalMillis = -1;
    private volatile String loadError = "";

    public RoadDetectionProcessor(RoadDetector detector) {
        this(detector, System::nanoTime, TrackingConfig.defaults(), TrajectoryConfig.defaults());
    }

    public RoadDetectionProcessor(RoadDetector detector, LongSupplier clock) {
        this(detector, clock, TrackingConfig.defaults(), TrajectoryConfig.defaults());
    }

    public RoadDetectionProcessor(RoadDetector detector, TrackingConfig config) {
        this(detector, System::nanoTime, config, TrajectoryConfig.defaults());
    }

    public RoadDetectionProcessor(RoadDetector detector, LongSupplier clock, TrackingConfig config) {
        this(detector, clock, config, TrajectoryConfig.defaults());
    }

    public RoadDetectionProcessor(RoadDetector detector, TrackingConfig tracking, TrajectoryConfig trajectory) {
        this(detector, System::nanoTime, tracking, trajectory);
    }

    public RoadDetectionProcessor(RoadDetector detector, LongSupplier clock, TrackingConfig tracking,
                                  TrajectoryConfig trajectory) {
        this(detector, clock, tracking, new LinearImageTrajectoryEstimator(trajectory));
    }

    /** Injectable for JVM tests or future image-only estimator implementations. */
    public RoadDetectionProcessor(RoadDetector detector, LongSupplier clock, TrackingConfig tracking,
                                  TrajectoryEstimator estimator) {
        this(detector, clock, tracking, estimator, PhysicalEstimationProcessor.unavailableByDefault());
    }

    /** Explicit configuration only: caller supplies calibration/priors; default has neither. */
    public RoadDetectionProcessor(RoadDetector detector, LongSupplier clock, TrackingConfig tracking,
                                  TrajectoryEstimator estimator, PhysicalEstimationProcessor physicalEstimator) {
        this(detector, clock, tracking, estimator, physicalEstimator,
                new RoadRiskEngine(RoadRiskConfig.defaults()));
    }

    /** Snapshot-based road-only risk; never consumes DriverState or emits alerts. */
    public RoadDetectionProcessor(RoadDetector detector, LongSupplier clock, TrackingConfig tracking,
                                  TrajectoryEstimator estimator, PhysicalEstimationProcessor physicalEstimator,
                                  RoadRiskEvaluator riskEvaluator) {
        this.detector = Objects.requireNonNull(detector);
        this.physicalEstimator = Objects.requireNonNull(physicalEstimator);
        this.roadRiskEvaluator = Objects.requireNonNull(riskEvaluator);
        this.clock = Objects.requireNonNull(clock);
        this.tracker = new ByteTrackInspiredTracker(tracking);
        this.trajectoryEstimator = Objects.requireNonNull(estimator);
        this.latest = new AtomicReference<>(DetectionSnapshot.unavailable(detector.info().modelId(), detector.state(), 0));
        this.trackingLatest = new AtomicReference<>(TrackingSnapshot.unavailable(0, TrackingSnapshot.Status.NOT_STARTED));
        this.trajectoryLatest = new AtomicReference<>(TrajectorySnapshot.unavailable(0,
                TrajectorySnapshot.Status.NOT_STARTED, TrackingSnapshot.Status.NOT_STARTED));
        this.physicalLatest = new AtomicReference<>(PhysicalEstimationSnapshot.unavailable(0,
                PhysicalEstimationSnapshot.Status.NOT_STARTED, TrackingSnapshot.Status.NOT_STARTED,
                TrajectorySnapshot.Status.NOT_STARTED));
        this.riskLatest = new AtomicReference<>(RoadRiskSnapshot.unavailable(0,
                RoadRiskSnapshot.Status.NOT_STARTED, TrackingSnapshot.Status.NOT_STARTED,
                TrajectorySnapshot.Status.NOT_STARTED, PhysicalEstimationSnapshot.Status.NOT_STARTED));
    }

    /** Loads the model. Returns false (and records the diagnostic) instead of throwing. */
    public boolean load() {
        try {
            detector.load();
            loadError = "";
            return true;
        } catch (ModelNotAvailableException e) {
            loadError = e.getMessage();
            ZLog.w(TAG, "model not available: " + e.getMessage());
            publishUnavailable();
            return false;
        }
    }

    @Override
    public void process(Frame frame) throws Exception {
        if (detector.state() != DetectorState.READY) {
            publishUnavailable(frame.timestampNanos());
            throw new DetectionException("detection unavailable: detector " + detector.state()
                    + (loadError.isEmpty() ? "" : " — " + loadError));
        }
        long t0 = clock.getAsLong();
        List<Detection> dets;
        try {
            dets = detector.detect(frame);
        } catch (DetectionException | RuntimeException e) {
            failureCount.incrementAndGet();
            publishUnavailable(frame.timestampNanos());
            throw e;
        }
        long t1 = clock.getAsLong();
        DetectorTimings timings = detector.lastTimings();
        double totalMs = (t1 - t0) / 1e6;
        emaTotalMillis = emaTotalMillis < 0 ? totalMs : 0.2 * totalMs + 0.8 * emaTotalMillis;
        inferenceFps.tick(t1);
        inferenceCount.incrementAndGet();
        latest.set(new DetectionSnapshot(frame.timestampNanos(), frame.uprightWidth(), frame.uprightHeight(),
                detector.info().modelId(), DetectorState.READY, true, dets, timings, sequence.incrementAndGet()));
        // Same processing thread: no second frame queue, no second camera pipeline. A tracking
        // error does not invalidate a genuinely successful detection, but is never shown as an
        // empty successful tracking result.
        TrackingSnapshot tracked;
        try {
            tracker.update(dets, frame.timestampNanos());
            tracked = new TrackingSnapshot(frame.timestampNanos(), frame.uprightWidth(),
                    frame.uprightHeight(), TrackingSnapshot.Status.READY, tracker.views());
            trackingLatest.set(tracked);
        } catch (RuntimeException e) {
            trajectoryLatest.set(TrajectorySnapshot.unavailable(frame.timestampNanos(),
                    TrajectorySnapshot.Status.TRACKING_UNAVAILABLE, TrackingSnapshot.Status.TRACKER_ERROR));
            trackingLatest.set(TrackingSnapshot.unavailable(frame.timestampNanos(), TrackingSnapshot.Status.TRACKER_ERROR));
            physicalEstimator.reset();
            physicalLatest.set(PhysicalEstimationSnapshot.unavailable(frame.timestampNanos(),
                    PhysicalEstimationSnapshot.Status.TRACKING_UNAVAILABLE, TrackingSnapshot.Status.TRACKER_ERROR,
                    TrajectorySnapshot.Status.TRACKING_UNAVAILABLE));
            riskLatest.set(RoadRiskSnapshot.unavailable(frame.timestampNanos(),
                    RoadRiskSnapshot.Status.TRACKING_UNAVAILABLE, TrackingSnapshot.Status.TRACKER_ERROR,
                    TrajectorySnapshot.Status.TRACKING_UNAVAILABLE,
                    PhysicalEstimationSnapshot.Status.TRACKING_UNAVAILABLE));
            throw e;
        }
        try {
            TrajectorySnapshot analyzed = Objects.requireNonNull(trajectoryEstimator.estimate(tracked), "trajectory result");
            if (!analyzed.available() || analyzed.frameTimestampNanos() != frame.timestampNanos()
                    || analyzed.uprightWidth() != frame.uprightWidth() || analyzed.uprightHeight() != frame.uprightHeight()) {
                throw new IllegalStateException("trajectory estimator returned mismatched/unavailable successful frame");
            }
            trajectoryLatest.set(analyzed);
        } catch (RuntimeException e) {
            trajectoryLatest.set(TrajectorySnapshot.unavailable(frame.timestampNanos(),
                    TrajectorySnapshot.Status.ESTIMATOR_ERROR, TrackingSnapshot.Status.READY));
            physicalEstimator.reset();
            physicalLatest.set(PhysicalEstimationSnapshot.unavailable(frame.timestampNanos(),
                    PhysicalEstimationSnapshot.Status.TRAJECTORY_UNAVAILABLE, TrackingSnapshot.Status.READY,
                    TrajectorySnapshot.Status.ESTIMATOR_ERROR));
            riskLatest.set(RoadRiskSnapshot.unavailable(frame.timestampNanos(),
                    RoadRiskSnapshot.Status.TRAJECTORY_UNAVAILABLE, TrackingSnapshot.Status.READY,
                    TrajectorySnapshot.Status.ESTIMATOR_ERROR,
                    PhysicalEstimationSnapshot.Status.TRAJECTORY_UNAVAILABLE));
            throw e;
        }
        PhysicalEstimationSnapshot physical;
        try {
            physical = Objects.requireNonNull(physicalEstimator.analyze(tracked, trajectoryLatest.get()),
                    "physical result");
        } catch (RuntimeException e) {
            physicalEstimator.reset();
            physicalLatest.set(PhysicalEstimationSnapshot.unavailable(frame.timestampNanos(),
                    PhysicalEstimationSnapshot.Status.ESTIMATOR_ERROR, TrackingSnapshot.Status.READY,
                    TrajectorySnapshot.Status.READY));
            riskLatest.set(RoadRiskSnapshot.unavailable(frame.timestampNanos(),
                    RoadRiskSnapshot.Status.PHYSICAL_UNAVAILABLE, TrackingSnapshot.Status.READY,
                    TrajectorySnapshot.Status.READY, PhysicalEstimationSnapshot.Status.ESTIMATOR_ERROR));
            throw e;
        }
        physicalLatest.set(physical);
        if (!physical.available() || physical.frameTimestampNanos() != frame.timestampNanos()
                || physical.uprightWidth() != frame.uprightWidth()
                || physical.uprightHeight() != frame.uprightHeight()) {
            riskLatest.set(RoadRiskSnapshot.unavailable(frame.timestampNanos(),
                    RoadRiskSnapshot.Status.PHYSICAL_UNAVAILABLE, TrackingSnapshot.Status.READY,
                    TrajectorySnapshot.Status.READY, physical.status()));
            throw new IllegalStateException("physical estimator rejected successful source frame: " + physical.status());
        }
        RoadRiskSnapshot risk;
        try {
            risk = Objects.requireNonNull(roadRiskEvaluator.evaluate(tracked, trajectoryLatest.get(), physical),
                    "road risk result");
        } catch (RuntimeException e) {
            riskLatest.set(RoadRiskSnapshot.unavailable(frame.timestampNanos(),
                    RoadRiskSnapshot.Status.ENGINE_ERROR, TrackingSnapshot.Status.READY,
                    TrajectorySnapshot.Status.READY, PhysicalEstimationSnapshot.Status.READY));
            throw e;
        }
        riskLatest.set(risk);
        if (!risk.available() || risk.frameTimestampNanos() != frame.timestampNanos()
                || risk.uprightWidth() != frame.uprightWidth()
                || risk.uprightHeight() != frame.uprightHeight()) {
            throw new IllegalStateException("risk evaluator rejected successful frame: " + risk.status());
        }
    }

    private void publishUnavailable() {
        publishUnavailable(0);
    }

    private void publishUnavailable(long frameTimestampNanos) {
        // Freeze track state/clock: detector failure is NOT a negative observation.
        trajectoryLatest.set(TrajectorySnapshot.unavailable(frameTimestampNanos,
                TrajectorySnapshot.Status.TRACKING_UNAVAILABLE, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE));
        trackingLatest.set(TrackingSnapshot.unavailable(frameTimestampNanos, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE));
        physicalEstimator.reset();
        physicalLatest.set(PhysicalEstimationSnapshot.unavailable(frameTimestampNanos,
                PhysicalEstimationSnapshot.Status.TRACKING_UNAVAILABLE, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE,
                TrajectorySnapshot.Status.TRACKING_UNAVAILABLE));
        riskLatest.set(RoadRiskSnapshot.unavailable(frameTimestampNanos,
                RoadRiskSnapshot.Status.TRACKING_UNAVAILABLE, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE,
                TrajectorySnapshot.Status.TRACKING_UNAVAILABLE,
                PhysicalEstimationSnapshot.Status.TRACKING_UNAVAILABLE));
        latest.set(DetectionSnapshot.unavailable(detector.info().modelId(), detector.state(), sequence.incrementAndGet()));
    }

    /** Thread-safe latest image-only trajectory result (never null). */
    public TrajectorySnapshot latestTrajectory() {
        return trajectoryLatest.get();
    }

    /** Thread-safe latest road-only risk diagnostics; no alerts or DriverGuard. */
    public RoadRiskSnapshot latestRoadRisk() { return riskLatest.get(); }

    /** Thread-safe latest physical diagnostics; Stage 4.2 reads without changing these values. */
    public PhysicalEstimationSnapshot latestPhysical() {
        return physicalLatest.get();
    }

    /** Thread-safe latest tracking result; independent of the detection snapshot. */
    public TrackingSnapshot latestTracking() {
        return trackingLatest.get();
    }

    /** Thread-safe latest snapshot (never null). */
    public DetectionSnapshot latest() {
        return latest.get();
    }

    public RoadDetector detector() {
        return detector;
    }

    public long inferenceCount() {
        return inferenceCount.get();
    }

    public long failureCount() {
        return failureCount.get();
    }

    /** Rolling inference FPS (processing clock) — 0 until two inferences. */
    public double inferenceFps() {
        return inferenceFps.fps();
    }

    /** EMA of total detector time per frame in ms; NaN until first inference. */
    public double recentTotalMillis() {
        return emaTotalMillis < 0 ? Double.NaN : emaTotalMillis;
    }

    @Override
    public void close() {
        detector.close();
    }

    @Override
    public String statusLine() {
        DetectorState s = detector.state();
        String model = detector.info().modelId();
        switch (s) {
            case READY: return "MODEL: " + model + "  STATUS: READY (" + detector.info().executionProvider() + ")";
            case ERROR: return "MODEL: " + model + "  STATUS: ERROR — DETECTION UNAVAILABLE";
            case NOT_LOADED: return "MODEL: " + model + "  STATUS: MODEL NOT AVAILABLE" + (loadError.isEmpty() ? "" : " — " + loadError);
            case LOADING: return "MODEL: " + model + "  STATUS: LOADING";
            case CLOSED:
            default: return "MODEL: " + model + "  STATUS: " + s;
        }
    }
}
