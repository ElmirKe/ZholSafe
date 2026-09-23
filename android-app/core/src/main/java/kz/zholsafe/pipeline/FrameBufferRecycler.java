package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;

/**
 * Optional hook a {@link FrameSource} can implement to get its frame buffers back once the
 * pipeline is done with them (after processing, after replacement in the queue, or at shutdown).
 * Lets the camera adapter keep a tiny fixed pool of buffers instead of allocating per frame.
 * Implementations must be thread-safe (called from camera and processing threads).
 */
public interface FrameBufferRecycler {

    void recycle(Frame frame);
}
