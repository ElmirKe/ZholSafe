package kz.zholsafe.ai;

import kz.zholsafe.model.Detection;

import java.util.List;

/**
 * RoadGuard detector contract: frame in, detections out.
 *
 * <p>Stage 2 implements this with {@code YoloDetector} (preprocessing → {@link OnnxModel} →
 * postprocessing/NMS). Implementations must be safe to call from a single dedicated inference
 * thread and must never block the UI thread. They must never return fabricated detections when
 * the underlying model is missing — throw {@link ModelNotAvailableException} at load time instead.
 */
public interface RoadDetector extends AutoCloseable {

    /** Loads the model; must be called once before {@link #detect(Frame)}. */
    void load() throws ModelNotAvailableException;

    boolean isReady();

    /** Runs detection on one frame. Returns an empty list when nothing is found. */
    List<Detection> detect(Frame frame) throws OnnxModel.InferenceException;

    /** Label mapping of the loaded model; only valid after {@link #load()}. */
    LabelMap labelMap();

    @Override
    void close();
}
