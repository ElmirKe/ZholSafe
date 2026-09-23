package kz.zholsafe.ai.preprocess;

import kz.zholsafe.model.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LetterboxTransformTest {

    @Test
    void landscapeIntoSquarePadsTopAndBottom() {
        LetterboxTransform t = LetterboxTransform.compute(1280, 720, 640, 640);
        assertEquals(0.5f, t.scale());
        assertEquals(640, t.newWidth());
        assertEquals(360, t.newHeight());
        assertEquals(0, t.padX());
        assertEquals(140, t.padY());
    }

    @Test
    void portraitIntoSquarePadsLeftAndRight() {
        LetterboxTransform t = LetterboxTransform.compute(720, 1280, 640, 640);
        assertEquals(360, t.newWidth());
        assertEquals(640, t.newHeight());
        assertEquals(140, t.padX());
        assertEquals(0, t.padY());
    }

    @Test
    void squareNeedsNoPadding() {
        LetterboxTransform t = LetterboxTransform.compute(320, 320, 640, 640);
        assertEquals(2f, t.scale());
        assertEquals(0, t.padX());
        assertEquals(0, t.padY());
    }

    @Test
    void oddRoundingUsesIntegerPadding() {
        LetterboxTransform t = LetterboxTransform.compute(1000, 333, 640, 640);
        assertEquals(640, t.newWidth());
        assertEquals(Math.round(333 * 0.64f), t.newHeight()); // 213
        assertEquals((640 - 213) / 2, t.padY());
    }

    @Test
    void inverseRemovesPaddingThenDividesByScale() {
        LetterboxTransform t = LetterboxTransform.compute(1280, 720, 640, 640);
        // model box covering the whole letterboxed content area → whole source image
        BoundingBox b = t.toSource(0, 140, 640, 500);
        assertEquals(new BoundingBox(0, 0, 1280, 720), b);
        // a box in the middle
        BoundingBox m = t.toSource(320, 320, 400, 400);
        assertEquals(640f, m.x1(), 1e-4);
        assertEquals(360f, m.y1(), 1e-4);
        assertEquals(800f, m.x2(), 1e-4);
        assertEquals(520f, m.y2(), 1e-4);
    }

    @Test
    void inverseIsNotIndependentAxisScaling() {
        LetterboxTransform t = LetterboxTransform.compute(1280, 720, 640, 640);
        BoundingBox b = t.toSource(0, 0, 640, 640);
        // naive scaling would give y2 = 720 from y=640; letterbox inverse clamps padding away instead
        assertEquals(0f, b.y1());
        assertEquals(720f, b.y2());
        // a box entirely inside the top padding maps to nothing
        assertNull(t.toSource(10, 10, 100, 100));
    }

    @Test
    void clampsToImageBounds() {
        LetterboxTransform t = LetterboxTransform.compute(640, 640, 640, 640);
        BoundingBox b = t.toSource(-50, -20, 700, 900);
        assertEquals(new BoundingBox(0, 0, 640, 640), b);
    }

    @Test
    void rejectsDegenerateAndNonFinite() {
        LetterboxTransform t = LetterboxTransform.compute(640, 640, 640, 640);
        assertNull(t.toSource(10, 10, 10, 50));
        assertNull(t.toSource(Float.NaN, 0, 10, 10));
        assertNull(t.toSource(0, 0, Float.POSITIVE_INFINITY, 10));
        assertNull(t.toSource(700, 700, 800, 800)); // fully outside → clamps to zero area
    }

    @Test
    void swappedCornersAreNormalised() {
        LetterboxTransform t = LetterboxTransform.compute(640, 640, 640, 640);
        BoundingBox b = t.toSource(100, 100, 50, 60);
        assertTrue(b.x1() < b.x2() && b.y1() < b.y2());
    }

    @Test
    void stretchModeScalesAxesIndependently() {
        LetterboxTransform t = LetterboxTransform.stretch(1280, 720, 640, 640);
        assertEquals(new BoundingBox(0, 0, 1280, 720), t.toSource(0, 0, 640, 640));
    }
}
