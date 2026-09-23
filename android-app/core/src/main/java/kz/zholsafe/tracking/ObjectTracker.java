package kz.zholsafe.tracking;

import kz.zholsafe.model.Detection;

import java.util.List;

/**
 * Multi-object tracker contract. Stage 4 provides an IoU/centroid-based implementation.
 *
 * <p>Implementations are stateful and single-threaded: call {@link #update} from the inference
 * pipeline thread only.
 */
public interface ObjectTracker {

    /**
     * Associates this frame's detections with existing tracks.
     *
     * @param detections     detections of the current frame (may be empty)
     * @param timestampNanos frame timestamp
     * @return all currently alive tracks (including coasting ones), never null
     */
    List<TrackedObject> update(List<Detection> detections, long timestampNanos);

    /** Drops all state (e.g. when the camera restarts or demo playback loops). */
    void reset();
}
