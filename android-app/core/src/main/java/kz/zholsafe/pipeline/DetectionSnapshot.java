package kz.zholsafe.pipeline;

import kz.zholsafe.ai.DetectorState;
import kz.zholsafe.ai.DetectorTimings;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable "latest result" published by {@link RoadDetectionProcessor} for UI / diagnostics.
 * Only the latest snapshot is retained — no history.
 *
 * <p>{@link #available()} distinguishes <b>NO DETECTIONS</b> (available, empty list) from
 * <b>DETECTION UNAVAILABLE</b> (not available: detector not READY or the last inference failed).
 * UI must never render an unavailable snapshot as "road clear".
 *
 * @param frameTimestampNanos source/image timestamp of the frame (see Frame)
 * @param uprightWidth        coordinate space width of the boxes (upright image)
 * @param uprightHeight       coordinate space height of the boxes
 * @param modelId             active model id
 * @param detectorState       detector state at publish time
 * @param available           false ⇒ detections list is meaningless (unavailable)
 * @param detections          canonical detections in upright pixels
 * @param timings             per-stage durations (monotonic clock)
 * @param sequence            monotonically increasing publish counter
 */
public record DetectionSnapshot(
        long frameTimestampNanos,
        int uprightWidth,
        int uprightHeight,
        String modelId,
        DetectorState detectorState,
        boolean available,
        List<Detection> detections,
        DetectorTimings timings,
        long sequence) {

    public DetectionSnapshot {
        detections = detections == null ? List.of() : List.copyOf(detections);
        timings = timings == null ? DetectorTimings.ZERO : timings;
    }

    public static DetectionSnapshot unavailable(String modelId, DetectorState state, long sequence) {
        return new DetectionSnapshot(0, 0, 0, modelId, state, false, List.of(), DetectorTimings.ZERO, sequence);
    }

    /** Count per canonical class (all classes present, zero if none). */
    public Map<ObjectClass, Integer> countByClass() {
        EnumMap<ObjectClass, Integer> m = new EnumMap<>(ObjectClass.class);
        for (ObjectClass c : ObjectClass.values()) {
            m.put(c, 0);
        }
        for (Detection d : detections) {
            m.merge(d.objectClass(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(m);
    }
}
