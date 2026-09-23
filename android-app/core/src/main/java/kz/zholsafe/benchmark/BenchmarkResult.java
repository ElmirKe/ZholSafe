package kz.zholsafe.benchmark;

import kz.zholsafe.ai.DetectorInfo;

import java.util.Locale;

/**
 * One benchmark run. Accuracy fields ({@link AccuracyResult}) are attached only when labelled
 * ground truth was evaluated — never estimated.
 */
public record BenchmarkResult(
        DetectorInfo detector,
        String deviceInfo,
        EvaluationCategory category,
        int frames,
        int warmUpFrames,
        int measuredFrames,
        int failures,
        int totalDetections,
        DetectorBenchmark.Stats total,
        DetectorBenchmark.Stats preprocess,
        DetectorBenchmark.Stats inference,
        DetectorBenchmark.Stats postprocess,
        double fps,
        long approxUsedHeapBytes) {

    /** Compact, machine-parsable one-liner for logs / reports. */
    public String toReportLine() {
        return String.format(Locale.ROOT,
                "model=%s provider=%s input=%dx%d category=%s device=\"%s\" frames=%d warmup=%d measured=%d failures=%d "
                        + "dets=%d total[mean=%.2f med=%.2f p95=%.2f]ms pre[mean=%.2f] inf[mean=%.2f med=%.2f p95=%.2f] post[mean=%.2f] fps=%.2f heapMB=%.1f",
                detector.modelId(), detector.executionProvider(), detector.inputWidth(), detector.inputHeight(), category,
                deviceInfo, frames, warmUpFrames, measuredFrames, failures, totalDetections,
                total.meanMs(), total.medianMs(), total.p95Ms(), preprocess.meanMs(),
                inference.meanMs(), inference.medianMs(), inference.p95Ms(), postprocess.meanMs(), fps,
                approxUsedHeapBytes / 1048576.0);
    }
}
