package kz.zholsafe.ai;

/**
 * Per-stage durations of the most recent {@link RoadDetector#detect} call, measured with the
 * local monotonic processing clock (never {@link Frame#timestampNanos()}).
 */
public record DetectorTimings(long preprocessNanos, long inferenceNanos, long postprocessNanos) {

    public static final DetectorTimings ZERO = new DetectorTimings(0, 0, 0);

    public long totalNanos() {
        return preprocessNanos + inferenceNanos + postprocessNanos;
    }
}
