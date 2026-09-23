package kz.zholsafe.model;

/**
 * Axis-aligned bounding box in image pixel coordinates.
 *
 * <p>Coordinates are in the coordinate system of the frame the detection was produced on
 * (after any rotation applied by the preprocessing step). {@code x1 <= x2}, {@code y1 <= y2}.
 */
public record BoundingBox(float x1, float y1, float x2, float y2) {

    public BoundingBox {
        if (Float.isNaN(x1) || Float.isNaN(y1) || Float.isNaN(x2) || Float.isNaN(y2)) {
            throw new IllegalArgumentException("BoundingBox coordinates must not be NaN");
        }
        if (x2 < x1 || y2 < y1) {
            throw new IllegalArgumentException(
                    "BoundingBox must satisfy x1<=x2 and y1<=y2, got " + x1 + "," + y1 + "," + x2 + "," + y2);
        }
    }

    public float width() {
        return x2 - x1;
    }

    public float height() {
        return y2 - y1;
    }

    public float area() {
        return width() * height();
    }

    public Point2D center() {
        return new Point2D((x1 + x2) / 2f, (y1 + y2) / 2f);
    }

    /** Intersection-over-union with another box; 0 when there is no overlap. */
    public float iou(BoundingBox other) {
        float ix1 = Math.max(x1, other.x1);
        float iy1 = Math.max(y1, other.y1);
        float ix2 = Math.min(x2, other.x2);
        float iy2 = Math.min(y2, other.y2);
        float iw = Math.max(0f, ix2 - ix1);
        float ih = Math.max(0f, iy2 - iy1);
        float inter = iw * ih;
        float union = area() + other.area() - inter;
        return union <= 0f ? 0f : inter / union;
    }
}
