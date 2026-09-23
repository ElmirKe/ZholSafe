package kz.zholsafe.ai.decode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic class-aware greedy NMS on {@link RawDetection}s (model-input coordinates).
 * Sort by confidence desc (ties: lower classIndex, then insertion order), keep a box unless it
 * overlaps an already-kept box <em>of the same class</em> with IoU &gt; threshold.
 */
public final class Nms {

    private Nms() { }

    public static List<RawDetection> classAware(List<RawDetection> in, float iouThreshold, int maxOut) {
        if (in.isEmpty()) {
            return List.of();
        }
        List<Integer> order = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingDouble(i -> -in.get(i).confidence())
                .thenComparingInt(i -> in.get(i).classIndex())
                .thenComparingInt(i -> i));
        List<RawDetection> kept = new ArrayList<>();
        for (int idx : order) {
            RawDetection cand = in.get(idx);
            boolean suppressed = false;
            for (RawDetection k : kept) {
                if (k.classIndex() == cand.classIndex() && iou(k, cand) > iouThreshold) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                kept.add(cand);
                if (kept.size() >= maxOut) {
                    break;
                }
            }
        }
        return kept;
    }

    static float iou(RawDetection a, RawDetection b) {
        float ix1 = Math.max(a.x1(), b.x1());
        float iy1 = Math.max(a.y1(), b.y1());
        float ix2 = Math.min(a.x2(), b.x2());
        float iy2 = Math.min(a.y2(), b.y2());
        float iw = Math.max(0f, ix2 - ix1);
        float ih = Math.max(0f, iy2 - iy1);
        float inter = iw * ih;
        float areaA = Math.max(0f, a.x2() - a.x1()) * Math.max(0f, a.y2() - a.y1());
        float areaB = Math.max(0f, b.x2() - b.x1()) * Math.max(0f, b.y2() - b.y1());
        float union = areaA + areaB - inter;
        return union <= 0f ? 0f : inter / union;
    }
}
