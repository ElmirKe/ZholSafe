package kz.zholsafe.benchmark;

import kz.zholsafe.ai.DetectionException;
import kz.zholsafe.ai.DetectorTimings;
import kz.zholsafe.ai.Frame;
import kz.zholsafe.ai.RoadDetector;
import kz.zholsafe.model.Detection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Runs a {@link RoadDetector} over a fixed frame sequence under identical conditions and collects
 * latency statistics. Pure Java: usable from JVM (with a JVM ONNX Runtime session) and from an
 * Android instrumentation/engineering entry point. It never invents numbers — if a detector is
 * not READY it throws.
 *
 * <p>Fair comparison rules (YOLO26n vs YOLO11n etc.): same device, same frames, same input
 * resolution policy (from each ModelSpec — report it), same warm-up, same clock. Thresholds are
 * per-spec and must be reported alongside results.
 */
public final class DetectorBenchmark {

    private final LongSupplier clock;

    public DetectorBenchmark() {
        this(System::nanoTime);
    }

    public DetectorBenchmark(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @param frames     frames to run in order (after warm-up frames); frames may repeat
     * @param warmUp     number of leading iterations excluded from statistics
     */
    public BenchmarkResult run(RoadDetector detector, List<Frame> frames, int warmUp, String deviceInfo,
                               EvaluationCategory category) throws DetectionException {
        if (!detector.isReady()) {
            throw new DetectionException("benchmark refused: detector state " + detector.state());
        }
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("no frames");
        }
        long[] total = new long[frames.size()];
        long[] pre = new long[frames.size()];
        long[] inf = new long[frames.size()];
        long[] post = new long[frames.size()];
        int detections = 0;
        int failures = 0;
        int measured = 0;
        for (int i = 0; i < warmUp; i++) {
            try {
                detector.detect(frames.get(i % frames.size()));
            } catch (DetectionException ignored) {
                failures++;
            }
        }
        long runStart = clock.getAsLong();
        for (Frame f : frames) {
            long t0 = clock.getAsLong();
            try {
                List<Detection> d = detector.detect(f);
                long t1 = clock.getAsLong();
                DetectorTimings t = detector.lastTimings();
                total[measured] = t1 - t0;
                pre[measured] = t.preprocessNanos();
                inf[measured] = t.inferenceNanos();
                post[measured] = t.postprocessNanos();
                detections += d.size();
                measured++;
            } catch (DetectionException e) {
                failures++;
            }
        }
        long runEnd = clock.getAsLong();
        Runtime rt = Runtime.getRuntime();
        long usedHeap = rt.totalMemory() - rt.freeMemory();
        return new BenchmarkResult(
                detector.info(), deviceInfo, category, frames.size(), warmUp, measured, failures, detections,
                Stats.of(Arrays.copyOf(total, measured)), Stats.of(Arrays.copyOf(pre, measured)),
                Stats.of(Arrays.copyOf(inf, measured)), Stats.of(Arrays.copyOf(post, measured)),
                measured == 0 ? 0 : measured / ((runEnd - runStart) / 1e9), usedHeap);
    }

    /** Latency statistics in milliseconds. */
    public record Stats(double meanMs, double medianMs, double p95Ms, double minMs, double maxMs) {
        public static Stats of(long[] nanos) {
            if (nanos.length == 0) {
                return new Stats(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            long[] s = nanos.clone();
            Arrays.sort(s);
            double sum = 0;
            for (long v : s) sum += v;
            return new Stats(sum / s.length / 1e6, percentile(s, 50) / 1e6, percentile(s, 95) / 1e6,
                    s[0] / 1e6, s[s.length - 1] / 1e6);
        }

        /** Nearest-rank percentile. */
        static long percentile(long[] sorted, int p) {
            int rank = (int) Math.ceil(p / 100.0 * sorted.length);
            return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
        }
    }

    /** Helper to build a repeated-frame sequence from a single synthetic frame. */
    public static List<Frame> repeat(Frame f, int n) {
        List<Frame> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(f);
        return l;
    }
}
