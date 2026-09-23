package kz.zholsafe.pipeline;

import kz.zholsafe.ai.Frame;

/**
 * STAGE 2 INSERTION POINT. Consumes one frame on the processing executor.
 *
 * <p>Stage 1 ships {@link DiagnosticFrameProcessor} (counters + cheap statistics, no AI).
 * Stage 2 will provide a processor that runs {@link kz.zholsafe.ai.RoadDetector} and emits
 * {@code List<Detection>} using the canonical {@link kz.zholsafe.model.ObjectClass} via a model
 * {@link kz.zholsafe.ai.LabelMap} — a model class index is NEVER assumed to equal an
 * {@code ObjectClass} ordinal.
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
