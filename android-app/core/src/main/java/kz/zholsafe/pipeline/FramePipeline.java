package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;
import kz.zholsafe.logging.ZLog;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Orchestrates {@code FrameSource → LatestFrameQueue → processing thread → FrameProcessor} and
 * maintains {@link PipelineTelemetry} + {@link PipelineState}. Pure Java: usable from Android
 * (LIVE, CameraX source) and from JVM tests (DEMO, synthetic source) with identical logic.
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>{@link #start()} / {@link #stop()} — caller's thread (Android main thread).</li>
 *   <li>{@link FrameSource.Listener#onFrame} — source's thread (CameraX analysis executor). Only
 *       enqueues + telemetry; never processes.</li>
 *   <li>Processing — one dedicated single-thread executor owned by this class ("zs-processing").
 *       Blocks on {@link LatestFrameQueue#take()}; runs {@link FrameProcessor#process}.</li>
 * </ul>
 * No per-frame threads, no unbounded queues: capacity is exactly one pending frame.
 *
 * <h2>Clock domains</h2>
 * The injected {@code clock} (default {@code System.nanoTime}) is used ONLY for processing
 * duration and FPS telemetry. {@link Frame#timestampNanos()} (source/image time) is passed
 * through to telemetry as an opaque value and is never subtracted from the local clock.
 *
 * <h2>Failure handling</h2>
 * Processor exceptions are counted and logged (rate-limited) and the pipeline continues in
 * {@link PipelineState#DEGRADED} until a frame succeeds again. Source errors move the pipeline to
 * {@link PipelineState#UNAVAILABLE} with a diagnostic; nothing is retried automatically.
 */
public final class FramePipeline implements FrameSource.Listener {

    private static final String TAG = "Pipeline";
    private static final long ERROR_LOG_EVERY = 30;

    private final FrameSource source;
    private final FrameProcessor processor;
    private final LatestFrameQueue queue;
    private final PipelineTelemetry telemetry;
    private final LongSupplier clock;
    private final FrameBufferRecycler recycler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ExecutorService executor;
    private volatile Thread worker;

    public FramePipeline(FrameSource source, FrameProcessor processor, PipelineTelemetry telemetry) {
        this(source, processor, telemetry, new LatestFrameQueue(), System::nanoTime);
    }

    public FramePipeline(FrameSource source, FrameProcessor processor, PipelineTelemetry telemetry,
                         LatestFrameQueue queue, LongSupplier clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recycler = source instanceof FrameBufferRecycler ? (FrameBufferRecycler) source : null;
    }

    public PipelineTelemetry telemetry() {
        return telemetry;
    }

    public LatestFrameQueue queue() {
        return queue;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Idempotent. Starts the processing thread first, then the source. */
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        telemetry.setState(PipelineState.STARTING, "starting source");
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "zs-processing");
            t.setDaemon(true);
            return t;
        });
        executor.execute(this::processingLoop);
        try {
            source.start(this);
            // Stays STARTING until the first frame arrives (CameraX binds asynchronously);
            // onFrame() flips to RUNNING, onSourceError() to UNAVAILABLE.
        } catch (RuntimeException e) {
            telemetry.setState(PipelineState.UNAVAILABLE, "source failed to start: " + e.getMessage());
            ZLog.e(TAG, "source failed to start", e);
        }
    }

    /** Idempotent. Stops the source, drains the queue, terminates the executor, closes processor. */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            source.stop();
        } catch (RuntimeException e) {
            ZLog.w(TAG, "source stop threw: " + e);
        }
        ExecutorService ex = executor;
        executor = null;
        if (ex != null) {
            ex.shutdownNow(); // interrupts take()
            try {
                if (!ex.awaitTermination(2, TimeUnit.SECONDS)) {
                    ZLog.w(TAG, "processing executor did not terminate within 2s");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        Frame pending = queue.clear();
        if (pending != null) {
            recycle(pending);
        }
        try {
            processor.close();
        } catch (RuntimeException e) {
            ZLog.w(TAG, "processor close threw: " + e);
        }
        telemetry.setState(PipelineState.STOPPED, "");
        ZLog.i(TAG, "pipeline stopped: " + telemetry.snapshot());
    }

    // ---- FrameSource.Listener (source thread) ----

    @Override
    public void onFrame(Frame frame) {
        if (!running.get()) {
            recycle(frame);
            return;
        }
        long now = clock.getAsLong();
        telemetry.onFrameReceived(frame.width(), frame.height(), frame.rotationDegrees(), frame.timestampNanos(), now);
        Frame displaced = queue.offer(frame);
        if (displaced != null) {
            telemetry.onFrameDropped();
            recycle(displaced);
        }
        if (telemetry.state() == PipelineState.STARTING) {
            telemetry.setState(PipelineState.RUNNING, "");
        }
    }

    @Override
    public void onSourceError(String diagnostic, Throwable cause) {
        telemetry.setState(PipelineState.UNAVAILABLE, diagnostic);
        ZLog.e(TAG, "source error: " + diagnostic, cause);
    }

    // ---- processing thread ----

    private void processingLoop() {
        long consecutiveErrors = 0;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            Frame frame;
            try {
                frame = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long t0 = clock.getAsLong();
            try {
                processor.process(frame);
                long t1 = clock.getAsLong();
                telemetry.onFrameProcessed(t1, (t1 - t0) / 1e6);
                if (consecutiveErrors > 0) {
                    consecutiveErrors = 0;
                    if (telemetry.state() == PipelineState.DEGRADED) {
                        telemetry.setState(PipelineState.RUNNING, "");
                    }
                }
            } catch (Throwable t) {
                telemetry.onProcessingError();
                consecutiveErrors++;
                telemetry.setState(PipelineState.DEGRADED, "processing error: " + t.getClass().getSimpleName());
                if (consecutiveErrors == 1 || consecutiveErrors % ERROR_LOG_EVERY == 0) {
                    ZLog.e(TAG, "frame processing failed (" + consecutiveErrors + " consecutive)", t);
                }
                if (t instanceof Error && !(t instanceof AssertionError)) {
                    throw (Error) t; // OOM etc. must not be swallowed
                }
            } finally {
                recycle(frame);
            }
        }
    }

    private void recycle(Frame frame) {
        if (recycler != null) {
            try {
                recycler.recycle(frame);
            } catch (RuntimeException e) {
                ZLog.w(TAG, "recycle failed: " + e);
            }
        }
    }
}
