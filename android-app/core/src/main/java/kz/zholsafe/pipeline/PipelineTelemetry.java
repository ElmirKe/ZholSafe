package kz.zholsafe.pipeline;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight, allocation-free runtime telemetry for the frame pipeline. Pure Java, no metrics
 * framework. Writers are the camera/processing threads; readers (UI) poll {@link #snapshot()}.
 *
 * <p>FPS is a rolling estimate over a fixed window of recent timestamps (exponential moving
 * average of inter-frame intervals) — reported as an estimate, not a measurement claim.
 */
public final class PipelineTelemetry {

    /** Immutable view for the UI. */
    public record Snapshot(
            PipelineState state,
            String stateDetail,
            long receivedFrames,
            long processedFrames,
            long droppedOrReplacedFrames,
            long processingErrors,
            long latestFrameTimestampNanos,
            int frameWidth,
            int frameHeight,
            int rotationDegrees,
            double receivedFps,
            double processedFps,
            double lastProcessingMillis) { }

    private static final double EMA_ALPHA = 0.2;

    private volatile PipelineState state = PipelineState.NOT_STARTED;
    private volatile String stateDetail = "";
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile long latestTimestampNanos;
    private volatile int width;
    private volatile int height;
    private volatile int rotation;
    private volatile double lastProcessingMillis;

    private final FpsEstimator receivedFps = new FpsEstimator();
    private final FpsEstimator processedFps = new FpsEstimator();

    public void setState(PipelineState newState, String detail) {
        this.state = newState;
        this.stateDetail = detail == null ? "" : detail;
    }

    public PipelineState state() {
        return state;
    }

    /** Camera/source thread. */
    public void onFrameReceived(int w, int h, int rotationDegrees, long timestampNanos, long nowNanos) {
        received.incrementAndGet();
        width = w;
        height = h;
        rotation = rotationDegrees;
        latestTimestampNanos = timestampNanos;
        receivedFps.tick(nowNanos);
    }

    public void onFrameDropped() {
        dropped.incrementAndGet();
    }

    /** Processing thread. */
    public void onFrameProcessed(long nowNanos, double processingMillis) {
        processed.incrementAndGet();
        lastProcessingMillis = processingMillis;
        processedFps.tick(nowNanos);
    }

    public void onProcessingError() {
        errors.incrementAndGet();
    }

    public long receivedFrames() {
        return received.get();
    }

    public long processedFrames() {
        return processed.get();
    }

    public long droppedOrReplacedFrames() {
        return dropped.get();
    }

    public long processingErrors() {
        return errors.get();
    }

    public Snapshot snapshot() {
        return new Snapshot(state, stateDetail, received.get(), processed.get(), dropped.get(), errors.get(),
                latestTimestampNanos, width, height, rotation,
                receivedFps.fps(), processedFps.fps(), lastProcessingMillis);
    }

    /** EMA of inter-arrival intervals; returns 0 until two ticks have been seen. */
    static final class FpsEstimator {
        private long lastNanos = -1;
        private double emaIntervalNanos = -1;

        synchronized void tick(long nowNanos) {
            if (lastNanos >= 0) {
                long dt = nowNanos - lastNanos;
                if (dt > 0) {
                    emaIntervalNanos = emaIntervalNanos < 0 ? dt : (EMA_ALPHA * dt + (1 - EMA_ALPHA) * emaIntervalNanos);
                }
            }
            lastNanos = nowNanos;
        }

        synchronized double fps() {
            return emaIntervalNanos <= 0 ? 0d : 1e9 / emaIntervalNanos;
        }

        synchronized void reset() {
            lastNanos = -1;
            emaIntervalNanos = -1;
        }
    }
}
