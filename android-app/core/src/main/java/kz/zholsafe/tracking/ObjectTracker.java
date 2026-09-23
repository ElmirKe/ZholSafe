package kz.zholsafe.tracking;

import kz.zholsafe.model.Detection;

import java.util.List;

/**
 * Multi-object tracker contract. Stage 3 provides a ByteTrack-inspired IoU implementation.
 *
 * <p>Implementations are stateful and single-threaded: call {@link #update} from the inference
 * pipeline thread only.
 */
public interface ObjectTracker {

    /**
     * Associates this frame's detections with existing tracks.
     *
     * @param detections     detections of a SUCCESSFUL detector run (may be empty). Never call
     *                       with an empty list to represent detector failure; freeze state instead.
     * @param timestampNanos positive strictly increasing same-source frame timestamp
     * @return all currently alive tracks (including coasting ones), never null
     */
    List<TrackedObject> update(List<Detection> detections, long timestampNanos);

    /** Drops all state (e.g. when the camera restarts or demo playback loops). */
    void reset();
}
