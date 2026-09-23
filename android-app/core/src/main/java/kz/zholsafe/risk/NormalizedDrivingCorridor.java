package kz.zholsafe.risk;

import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Contracts;

/** Trapezoidal approximate forward path, in upright normalized frame coordinates. NOT lane detection. */
public record NormalizedDrivingCorridor(double centerX, double topY, double topHalfWidth,
                                        double bottomHalfWidth) {
    public NormalizedDrivingCorridor {
        Contracts.range("centerX", centerX, 0d, 1d);
        Contracts.range("topY", topY, 0d, 1d);
        Contracts.range("topHalfWidth", topHalfWidth, 0d, 1d);
        Contracts.range("bottomHalfWidth", bottomHalfWidth, 0d, 1d);
        if (topY >= 1d || topHalfWidth <= 0d || bottomHalfWidth <= topHalfWidth
                || centerX - bottomHalfWidth < 0d || centerX + bottomHalfWidth > 1d) {
            throw new IllegalArgumentException("trapezoid must widen downward and fit the image");
        }
    }

    public double halfWidthAt(double normalizedY) {
        Contracts.range("normalizedY", normalizedY, topY, 1d);
        return topHalfWidth + (bottomHalfWidth - topHalfWidth) * (normalizedY - topY) / (1d - topY);
    }

    /** Inclusive boundary intersection; CENTRAL requires contact in central fraction of corridor. */
    public CorridorRelation classify(BoundingBox box, int width, int height,
                                     double nearMargin, double centralFraction) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("invalid frame dimensions");
        Contracts.range("nearMargin", nearMargin, 0d, 1d);
        Contracts.range("centralFraction", centralFraction, 0d, 1d);
        if (!Float.isFinite(box.x1()) || !Float.isFinite(box.x2())
                || !Float.isFinite(box.y1()) || !Float.isFinite(box.y2())
                || box.x1() < 0 || box.x2() > width || box.y1() < 0 || box.y2() > height) {
            throw new IllegalArgumentException("bbox outside upright image");
        }
        double y = (double) box.y2() / height;
        if (y < topY - nearMargin) return CorridorRelation.OUTSIDE;
        double sampledY = Math.max(topY, Math.min(1d, y));
        double half = halfWidthAt(sampledY);
        double left = centerX - half, right = centerX + half;
        double x1 = (double) box.x1() / width, x2 = (double) box.x2() / width;
        double contactX = ((double) box.x1() + box.x2()) / (2d * width);
        if (y >= topY && contactX >= centerX - centralFraction * half
                && contactX <= centerX + centralFraction * half) return CorridorRelation.CENTRAL;
        if (y >= topY && x2 >= left && x1 <= right) return CorridorRelation.INTERSECTING;
        return x2 >= left - nearMargin && x1 <= right + nearMargin
                ? CorridorRelation.NEAR : CorridorRelation.OUTSIDE;
    }

    /** Projected contact position, not a collision point; boundary is inclusive. */
    public boolean containsContact(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || y < topY || y > 1d) return false;
        double half = halfWidthAt(y);
        return x >= centerX - half && x <= centerX + half;
    }
}
