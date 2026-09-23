package kz.zholsafe.ai.decode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NmsTest {

    static RawDetection box(float x, float y, float w, float h, float conf, int cls) {
        return new RawDetection(x, y, x + w, y + h, conf, cls);
    }

    @Test
    void emptyInput() {
        assertTrue(Nms.classAware(List.of(), 0.5f, 100).isEmpty());
    }

    @Test
    void highOverlapSameClassKeepsHighestConfidence() {
        List<RawDetection> out = Nms.classAware(List.of(
                box(0, 0, 100, 100, 0.7f, 0), box(5, 5, 100, 100, 0.9f, 0), box(2, 2, 100, 100, 0.8f, 0)), 0.5f, 100);
        assertEquals(1, out.size());
        assertEquals(0.9f, out.get(0).confidence());
    }

    @Test
    void lowOverlapBothKept() {
        List<RawDetection> out = Nms.classAware(List.of(box(0, 0, 100, 100, 0.7f, 0), box(90, 90, 100, 100, 0.9f, 0)), 0.5f, 100);
        assertEquals(2, out.size());
    }

    @Test
    void differentClassesNotSuppressed() {
        List<RawDetection> out = Nms.classAware(List.of(box(0, 0, 100, 100, 0.7f, 0), box(0, 0, 100, 100, 0.9f, 1)), 0.5f, 100);
        assertEquals(2, out.size());
    }

    @Test
    void thresholdBoundaryIsStrictlyGreater() {
        // IoU exactly 0.5: boxes [0,100]x[0,100] and [0,100]x[50,150] → inter 5000, union 15000 = 0.333; use x-shift 1/3
        RawDetection a = box(0, 0, 90, 100, 0.9f, 0);
        RawDetection b = box(30, 0, 90, 100, 0.8f, 0); // inter 60*100=6000, union 18000-6000=12000 → 0.5
        assertEquals(0.5f, Nms.iou(a, b), 1e-6);
        assertEquals(2, Nms.classAware(List.of(a, b), 0.5f, 100).size(), "IoU == threshold is kept");
        assertEquals(1, Nms.classAware(List.of(a, b), 0.49f, 100).size());
    }

    @Test
    void deterministicOrderAndMaxOut() {
        List<RawDetection> in = List.of(box(0, 0, 10, 10, 0.5f, 1), box(50, 50, 10, 10, 0.5f, 0), box(100, 100, 10, 10, 0.5f, 0));
        List<RawDetection> a = Nms.classAware(in, 0.5f, 100);
        List<RawDetection> b = Nms.classAware(in, 0.5f, 100);
        assertEquals(a, b);
        assertEquals(0, a.get(0).classIndex(), "ties broken by lower class index then insertion order");
        assertEquals(2, Nms.classAware(in, 0.5f, 2).size());
    }
}
