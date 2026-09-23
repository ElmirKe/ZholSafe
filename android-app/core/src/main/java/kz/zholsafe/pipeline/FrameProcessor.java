package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;

/**
 * Consumes one frame on the processing executor.
 *
 * <p>Implementations: {@link DiagnosticFrameProcessor} (Stage 1, no AI) and
 * {@link RoadDetectionProcessor} (Stage 2, runs a {@link kz.zholsafe.ai.RoadDetector} and
 * publishes a {@link DetectionSnapshot}). Model class indices reach canonical
 * {@link kz.zholsafe.model.ObjectClass} only through {@link kz.zholsafe.ai.LabelMap}.
 *
 * <p>Contract: called from exactly one thread at a time; must not block indefinitely; must not
 * retain {@code frame.data()} after returning (the buffer is recycled). Exceptions are caught by
 * {@link FramePipeline}, counted in telemetry and logged — they do not stop the pipeline.
 */
public interface FrameProcessor {

    void process(Frame frame) throws Exception;

    /** Called once when the pipeline stops; release native/model resources here. */
    default void close() { }

    /** Short human-readable status for telemetry UI, e.g. "NOT LOADED — STAGE 2". */
    default String statusLine() {
        return getClass().getSimpleName();
    }
}
