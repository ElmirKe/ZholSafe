package kz.zholsafe.model;

/** Immutable 2D point in image pixel coordinates. */
public record Point2D(float x, float y) {

    public float distanceTo(Point2D other) {
        float dx = x - other.x;
        float dy = y - other.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
