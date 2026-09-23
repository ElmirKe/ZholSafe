package kz.zholsafe.pipeline;

import kz.zholsafe.ai.DetectionException;
import kz.zholsafe.ai.DetectorState;
import kz.zholsafe.ai.DetectorTimings;
import kz.zholsafe.ai.Frame;
import kz.zholsafe.ai.ModelNotAvailableException;
import kz.zholsafe.ai.RoadDetector;
import kz.zholsafe.config.TrackingConfig;
import kz.zholsafe.tracking.ByteTrackInspiredTracker;
import kz.zholsafe.logging.ZLog;
import kz.zholsafe.model.Detection;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Stage 2/3 {@link FrameProcessor}: runs the {@link RoadDetector} then the tracker on the
 * same processing thread; publishes separate {@link DetectionSnapshot} and
 * {@link TrackingSnapshot} results.
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
    private final AtomicReference<TrackingSnapshot> trackingLatest;
    private final LongSupplier clock;
    private final AtomicReference<DetectionSnapshot> latest;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong inferenceCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final PipelineTelemetry.FpsEstimator inferenceFps = new PipelineTelemetry.FpsEstimator();
    private volatile double emaTotalMillis = -1;
    private volatile String loadError = "";

    public RoadDetectionProcessor(RoadDetector detector) {
        this(detector, System::nanoTime, TrackingConfig.defaults());
    }

    public RoadDetectionProcessor(RoadDetector detector, LongSupplier clock) {
        this(detector, clock, TrackingConfig.defaults());
    }

    public RoadDetectionProcessor(RoadDetector detector, TrackingConfig config) {
        this(detector, System::nanoTime, config);
    }

    public RoadDetectionProcessor(RoadDetector detector, LongSupplier clock, TrackingConfig config) {
        this.detector = Objects.requireNonNull(detector);
        this.clock = Objects.requireNonNull(clock);
        this.tracker = new ByteTrackInspiredTracker(config);
        this.latest = new AtomicReference<>(DetectionSnapshot.unavailable(detector.info().modelId(), detector.state(), 0));
        this.trackingLatest = new AtomicReference<>(TrackingSnapshot.unavailable(0, TrackingSnapshot.Status.NOT_STARTED));
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
        try {
            tracker.update(dets, frame.timestampNanos());
            trackingLatest.set(new TrackingSnapshot(frame.timestampNanos(), frame.uprightWidth(),
                    frame.uprightHeight(), TrackingSnapshot.Status.READY, tracker.views()));
        } catch (RuntimeException e) {
            trackingLatest.set(TrackingSnapshot.unavailable(frame.timestampNanos(), TrackingSnapshot.Status.TRACKER_ERROR));
            throw e;
        }
    }

    private void publishUnavailable() {
        publishUnavailable(0);
    }

    private void publishUnavailable(long frameTimestampNanos) {
        latest.set(DetectionSnapshot.unavailable(detector.info().modelId(), detector.state(), sequence.incrementAndGet()));
        // Freeze track state/clock: detector failure is NOT a negative observation.
        trackingLatest.set(TrackingSnapshot.unavailable(frameTimestampNanos, TrackingSnapshot.Status.DETECTOR_UNAVAILABLE));
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
