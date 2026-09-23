package kz.zholsafe.physical;

import kz.zholsafe.model.BoundingBox;

/** Reject nonfinite, degenerate, clipped/edge-touching boxes: inferred contact/height is lost. */
final class BoxGeometry {
    private BoxGeometry() { }

    static boolean fullVisible(BoundingBox b, int width, int height, int minHeight) {
        return Float.isFinite(b.x1()) && Float.isFinite(b.x2())
                && Float.isFinite(b.y1()) && Float.isFinite(b.y2())
                && b.x1() > 0f && b.y1() > 0f && b.x2() < width && b.y2() < height
                && b.width() > 0f && b.height() >= minHeight;
    }
}
