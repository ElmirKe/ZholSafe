package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-slot, drop-oldest hand-off between the camera thread and the inference thread.
 *
 * <p>Backpressure policy for real-time safety: a recent frame is more valuable than a backlog.
 * If inference is slower than capture, intermediate frames are discarded and counted in
 * {@link #droppedCount()} (useful for profiling in Stage 7). Never blocks the producer.
 */
public final class LatestFrameQueue {

    private Frame latest;
    private final AtomicLong dropped = new AtomicLong();

    /** Producer side: replaces any pending frame. Never blocks. */
    public synchronized void offer(Frame frame) {
        if (latest != null) {
            dropped.incrementAndGet();
        }
        latest = frame;
        notifyAll();
    }

    /** Consumer side: blocks until a frame is available or the thread is interrupted. */
    public synchronized Frame take() throws InterruptedException {
        while (latest == null) {
            wait();
        }
        Frame f = latest;
        latest = null;
        return f;
    }

    /** Consumer side: non-blocking. */
    public synchronized Frame poll() {
        Frame f = latest;
        latest = null;
        return f;
    }

    public long droppedCount() {
        return dropped.get();
    }

    public synchronized boolean isEmpty() {
        return latest == null;
    }
}
