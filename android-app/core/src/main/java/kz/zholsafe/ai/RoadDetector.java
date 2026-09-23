package kz.zholsafe.ai;

import kz.zholsafe.model.Detection;

import java.util.List;

/**
 * Road-object detector contract. Android-independent: nothing here exposes ONNX Runtime types,
 * ImageProxy, Bitmap or Context. The pipeline does not know which model family is active.
 *
 * <h2>Coordinate convention (binding for all implementations)</h2>
 * Returned {@link Detection#box()} coordinates are <b>pixels in the UPRIGHT source image</b>, i.e.
 * the camera buffer after applying {@link Frame#rotationDegrees()}, spanning
 * {@code [0, frame.uprightWidth()] × [0, frame.uprightHeight()]}, origin top-left, x right, y down.
 * Boxes are clamped to those bounds and satisfy {@code x1 < x2}, {@code y1 < y2}, all finite.
 * Model-input (letterboxed) coordinates and raw camera-buffer coordinates never leak out.
 *
 * <h2>Semantics</h2>
 * An empty list means "model ran and found nothing above threshold" (NO DETECTIONS). A thrown
 * {@link DetectionException} or a non-READY state means DETECTION UNAVAILABLE. Implementations
 * must never return an empty list to hide a failure.
 */
public interface RoadDetector extends AutoCloseable {

    /** Loads the model synchronously. Fails fast with a diagnostic; state becomes READY or ERROR. */
    void load() throws ModelNotAvailableException;

    DetectorState state();

    default boolean isReady() {
        return state() == DetectorState.READY;
    }

    /**
     * Runs one frame. Called serially from the processing thread. Must not retain
     * {@code frame.data()} after returning.
     */
    List<Detection> detect(Frame frame) throws DetectionException;

    /** Model-specific label map (model class index → canonical class). */
    LabelMap labelMap();

    DetectorInfo info();

    /** Last per-stage timings in nanoseconds (monotonic processing clock); zeros if not yet run. */
    DetectorTimings lastTimings();

    @Override
    void close();
}
