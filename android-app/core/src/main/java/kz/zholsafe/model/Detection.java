package kz.zholsafe.model;

/**
 * DATA CONTRACT: a single raw object detection produced by a detector for one frame.
 *
 * <p>A detection is NOT a risk. It only states that the model believes an object of
 * {@link #objectClass()} is present at {@link #box()} with {@link #confidence()}.
 * Risk is derived later by the Risk Engine from tracked objects, trajectory, driver state and
 * vehicle context. See {@code docs/DATA_CONTRACTS.md}.
 *
 * @param classId        raw class index emitted by the model (kept for diagnostics / label-map issues)
 * @param objectClass    mapped canonical class; {@link ObjectClass#UNKNOWN} if the index is unmapped
 * @param confidence     model confidence in [0,1]
 * @param box            bounding box in frame pixel coordinates
 * @param timestampNanos monotonic timestamp of the source frame (see {@code Frame#timestampNanos()})
 */
public record Detection(
        int classId,
        ObjectClass objectClass,
        float confidence,
        BoundingBox box,
        long timestampNanos) {

    public Detection {
        if (objectClass == null) {
            throw new IllegalArgumentException("objectClass must not be null");
        }
        if (box == null) {
            throw new IllegalArgumentException("box must not be null");
        }
        Contracts.unit("confidence", confidence);
    }

    /** Convenience accessor kept for readability at call sites. */
    public String className() {
        return objectClass.label();
    }
}
