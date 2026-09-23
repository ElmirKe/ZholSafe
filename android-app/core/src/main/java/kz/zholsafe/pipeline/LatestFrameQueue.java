package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-slot, drop-oldest hand-off between the camera thread and the processing thread.
 *
 * <p>Backpressure policy for real-time safety: a recent frame is more valuable than a backlog.
 * If processing is slower than capture, the pending frame is <em>replaced</em> and counted in
 * {@link #droppedCount()}. Never blocks the producer. Capacity is exactly one; there is no way
 * to make it grow.
 *
 * <p>Stage 1 addition: {@link #offer(Frame)} returns the displaced frame (if any) so the producer
 * can recycle its buffer immediately, and {@link #clear()} lets shutdown release a pending frame.
 */
public final class LatestFrameQueue {

    private Frame latest;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong offered = new AtomicLong();

    /**
     * Producer side: replaces any pending frame. Never blocks.
     *
     * @return the frame that was displaced (its buffer may now be recycled), or {@code null}
     */
    public synchronized Frame offer(Frame frame) {
        offered.incrementAndGet();
        Frame displaced = latest;
        if (displaced != null) {
            dropped.incrementAndGet();
        }
        latest = frame;
        notifyAll();
        return displaced;
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

    /** Removes and returns any pending frame without counting it as dropped (shutdown use). */
    public synchronized Frame clear() {
        Frame f = latest;
        latest = null;
        return f;
    }

    /** Frames replaced before being consumed. */
    public long droppedCount() {
        return dropped.get();
    }

    /** Total frames offered by producers. */
    public long offeredCount() {
        return offered.get();
    }

    public synchronized boolean isEmpty() {
        return latest == null;
    }
}
