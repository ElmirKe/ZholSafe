package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;

/**
 * Source of frames for the pipeline. LIVE mode: CameraX-backed (app module). DEMO mode:
 * pre-recorded video or deterministic synthetic frames. Both feed the SAME downstream pipeline
 * and Risk Engine — there is exactly one processing path.
 */
public interface FrameSource extends AutoCloseable {

    interface Listener {
        /** Called on the source's own thread; must return quickly (enqueue, don't process). */
        void onFrame(Frame frame);

        void onSourceError(String diagnostic, Throwable cause);
    }

    void start(Listener listener);

    void stop();

    boolean isRunning();

    @Override
    default void close() {
        stop();
    }
}
