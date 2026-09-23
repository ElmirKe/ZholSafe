package kz.zholsafe.benchmark;

import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;

import java.util.ArrayList;
import java.util.List;

/**
 * TP / FP / FN / precision / recall against labelled ground truth (class-aware greedy IoU
 * matching). Requires real labels: there is no way to construct one without them.
 */
public record AccuracyResult(int tp, int fp, int fn, double iouThreshold) {

    public record GroundTruth(ObjectClass objectClass, BoundingBox box) { }

    public double precision() {
        return tp + fp == 0 ? Double.NaN : (double) tp / (tp + fp);
    }

    public double recall() {
        return tp + fn == 0 ? Double.NaN : (double) tp / (tp + fn);
    }

    public static AccuracyResult evaluate(List<Detection> predictions, List<GroundTruth> truth, double iouThreshold) {
        List<Detection> preds = new ArrayList<>(predictions);
        preds.sort((a, b) -> Float.compare(b.confidence(), a.confidence()));
        boolean[] matched = new boolean[truth.size()];
        int tp = 0;
        int fp = 0;
        for (Detection p : preds) {
            int best = -1;
            double bestIou = iouThreshold;
            for (int i = 0; i < truth.size(); i++) {
                GroundTruth g = truth.get(i);
                if (matched[i] || g.objectClass() != p.objectClass()) continue;
                double iou = p.box().iou(g.box());
                if (iou >= bestIou) {
                    bestIou = iou;
                    best = i;
                }
            }
            if (best >= 0) {
                matched[best] = true;
                tp++;
            } else {
                fp++;
            }
        }
        int fn = 0;
        for (boolean m : matched) if (!m) fn++;
        return new AccuracyResult(tp, fp, fn, iouThreshold);
    }

    public AccuracyResult plus(AccuracyResult o) {
        return new AccuracyResult(tp + o.tp, fp + o.fp, fn + o.fn, iouThreshold);
    }
}
