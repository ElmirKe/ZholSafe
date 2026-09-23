package kz.zholsafe.trajectory;

import kz.zholsafe.config.TrajectoryConfig;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;
import kz.zholsafe.tracking.TrackObservation;
import kz.zholsafe.tracking.TrackState;
import kz.zholsafe.tracking.TrackView;
import kz.zholsafe.tracking.TrackedObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stateless multi-sample least-squares image-only fit. Uses source nanoseconds from matched
 * Stage 3 observations, not process time or frame index. No physical velocity, range or TTC.
 * Near-zero span, invalid/stale history or excessive RMS yields explicit unavailable motion.
 */
public final class LinearImageTrajectoryEstimator implements TrajectoryEstimator {
    private static final double NANOS_PER_SECOND = 1_000_000_000d; // unit conversion, not a threshold
    private final TrajectoryConfig config;

    public LinearImageTrajectoryEstimator(TrajectoryConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public TrajectorySnapshot estimate(TrackingSnapshot tracking) {
        Objects.requireNonNull(tracking, "tracking");
        if (!tracking.available()) {
            TrajectorySnapshot.Status status = tracking.status() == TrackingSnapshot.Status.NOT_STARTED
                    ? TrajectorySnapshot.Status.NOT_STARTED : TrajectorySnapshot.Status.TRACKING_UNAVAILABLE;
            return TrajectorySnapshot.unavailable(tracking.frameTimestampNanos(), status, tracking.status());
        }
        int w = tracking.uprightWidth();
        int h = tracking.uprightHeight();
        // TrackingSnapshot READY already enforces positive dimensions and timestamp.
        List<ObjectTrajectory> result = new ArrayList<>(tracking.tracks().size());
        for (TrackView track : tracking.tracks()) {
            result.add(analyze(track, tracking.frameTimestampNanos(), w, h));
        }
        return new TrajectorySnapshot(tracking.frameTimestampNanos(), w, h, TrajectorySnapshot.Status.READY,
                tracking.status(), result);
    }

    private ObjectTrajectory analyze(TrackView view, long frameTs, int w, int h) {
        TrackedObject object = view.object();
        List<TrackObservation> all = view.history();
        int count = Math.min(all.size(), config.maxSamples());
        List<TrackObservation> recent = all.subList(all.size() - count, all.size());
        if (view.state() != TrackState.CONFIRMED || view.missedFrames() != 0
                || object.timestampNanos() != frameTs) {
            return unavailable(object, count, TrajectoryStatus.TRACK_NOT_CURRENT);
        }
        long last = 0;
        for (TrackObservation obs : recent) {
            if (obs.timestampNanos() <= last) return unavailable(object, count, TrajectoryStatus.INVALID_TIMESTAMPS);
            last = obs.timestampNanos();
        }
        if (last != object.timestampNanos()) return unavailable(object, count, TrajectoryStatus.INVALID_TIMESTAMPS);
        if (count < config.minSamples()) return unavailable(object, count, TrajectoryStatus.INSUFFICIENT_HISTORY);

        long first = recent.get(0).timestampNanos();
        double[] times = new double[count];
        double[] x = new double[count];
        double[] y = new double[count];
        double[] logArea = new double[count];
        double currentArea = Double.NaN;
        for (int i = 0; i < count; i++) {
            TrackObservation obs = recent.get(i);
            BoundingBox b = obs.box();
            if (!validBox(b, w, h)) return unavailable(object, count, TrajectoryStatus.LOW_QUALITY);
            long nanos;
            try {
                nanos = Math.subtractExact(obs.timestampNanos(), first);
            } catch (ArithmeticException ex) {
                return unavailable(object, count, TrajectoryStatus.INVALID_TIMESTAMPS);
            }
            times[i] = nanos / NANOS_PER_SECOND;
            x[i] = ((double) b.x1() + b.x2()) / (2d * w);
            y[i] = ((double) b.y1() + b.y2()) / (2d * h);
            currentArea = (((double) b.x2() - b.x1()) / w) * (((double) b.y2() - b.y1()) / h);
            logArea[i] = Math.log(currentArea);
            if (!Double.isFinite(logArea[i])) return unavailable(object, count, TrajectoryStatus.LOW_QUALITY);
        }
        double span = times[count - 1];
        if (span < config.minimumTimeSpanSeconds()) {
            return unavailable(object, count, TrajectoryStatus.LOW_QUALITY);
        }
        Fit centerX = fit(times, x);
        Fit centerY = fit(times, y);
        Fit areaFit = fit(times, logArea);
        if (centerX == null || centerY == null || areaFit == null) {
            return unavailable(object, count, TrajectoryStatus.LOW_QUALITY);
        }
        double centerRms = Math.hypot(centerX.rms, centerY.rms);
        double imageSpeed = Math.hypot(centerX.slope, centerY.slope);
        if (!Double.isFinite(centerRms) || !Double.isFinite(imageSpeed)
                || centerRms > config.maxCenterRmsFraction()
                || areaFit.rms > config.maxLogAreaRms()) {
            return unavailable(object, count, TrajectoryStatus.LOW_QUALITY);
        }
        ImageDirection direction = direction(centerX.slope, centerY.slope, imageSpeed);
        ImageMotion motion = new ImageMotion(true, centerX.slope, centerY.slope, imageSpeed, direction);
        ScaleChange change = scaleChange(areaFit.slope, times, logArea);
        ImageScaleTrend scale = new ImageScaleTrend(true, currentArea, areaFit.slope, change);
        ApproachState approach = switch (change) {
            case GROWING -> ApproachState.APPROACHING;
            case SHRINKING -> ApproachState.RECEDING;
            case STABLE -> ApproachState.LATERAL_OR_STABLE;
            case UNCERTAIN -> ApproachState.UNCERTAIN;
        };
        return new ObjectTrajectory(object.trackId(), object.objectClass(), object.timestampNanos(),
                object.box(), count, TrajectoryStatus.AVAILABLE, TrajectoryQuality.FIT_ACCEPTED,
                motion, scale, approach, span, centerRms, areaFit.rms);
    }

    private static boolean validBox(BoundingBox b, int w, int h) {
        return Float.isFinite(b.x1()) && Float.isFinite(b.x2()) && Float.isFinite(b.y1())
                && Float.isFinite(b.y2()) && b.x1() >= 0 && b.y1() >= 0
                && b.x2() <= w && b.y2() <= h && b.x2() > b.x1() && b.y2() > b.y1();
    }

    /** Ordinary least squares with centred source-time coordinates to avoid cancellation. */
    private static Fit fit(double[] times, double[] values) {
        int n = times.length;
        double meanT = 0d, meanV = 0d;
        for (int i = 0; i < n; i++) { meanT += times[i]; meanV += values[i]; }
        meanT /= n;
        meanV /= n;
        double varianceT = 0d, covariance = 0d;
        for (int i = 0; i < n; i++) {
            double centeredT = times[i] - meanT;
            varianceT += centeredT * centeredT;
            covariance += centeredT * (values[i] - meanV);
        }
        if (!(varianceT > 0d) || !Double.isFinite(varianceT)) return null;
        double slope = covariance / varianceT;
        double squaredError = 0d;
        for (int i = 0; i < n; i++) {
            double error = values[i] - (meanV + slope * (times[i] - meanT));
            squaredError += error * error;
        }
        double rms = Math.sqrt(squaredError / n);
        return Double.isFinite(slope) && Double.isFinite(rms) ? new Fit(slope, rms) : null;
    }

    private ImageDirection direction(double vx, double vy, double speed) {
        if (speed <= config.stationaryVelocityThreshold()) return ImageDirection.STATIONARY;
        if (Math.abs(vy) < config.diagonalComponentRatio() * Math.abs(vx)) {
            return vx > 0d ? ImageDirection.RIGHT : ImageDirection.LEFT;
        }
        if (Math.abs(vx) < config.diagonalComponentRatio() * Math.abs(vy)) {
            return vy > 0d ? ImageDirection.DOWN : ImageDirection.UP;
        }
        if (vx > 0d) return vy > 0d ? ImageDirection.DOWN_RIGHT : ImageDirection.UP_RIGHT;
        return vy > 0d ? ImageDirection.DOWN_LEFT : ImageDirection.UP_LEFT;
    }

    private ScaleChange scaleChange(double rate, double[] times, double[] logArea) {
        if (Math.abs(rate) <= config.scaleStableThreshold()) return ScaleChange.STABLE;
        // A regression slope alone can be driven by a single late bbox jump. Require every
        // consecutive observed step to exceed the configured stable band in the same direction
        // before making even a qualitative growth/shrink claim (conservative MVP policy).
        boolean sustainedGrowth = true;
        boolean sustainedShrink = true;
        for (int i = 1; i < times.length; i++) {
            double stepRate = (logArea[i] - logArea[i - 1]) / (times[i] - times[i - 1]);
            if (stepRate <= config.scaleStableThreshold()) sustainedGrowth = false;
            if (stepRate >= -config.scaleStableThreshold()) sustainedShrink = false;
        }
        if (rate >= config.approachGrowthThreshold() && sustainedGrowth) return ScaleChange.GROWING;
        if (rate <= -config.recedeShrinkThreshold() && sustainedShrink) return ScaleChange.SHRINKING;
        return ScaleChange.UNCERTAIN;
    }

    private static ObjectTrajectory unavailable(TrackedObject object, int count, TrajectoryStatus status) {
        return ObjectTrajectory.unavailable(object.trackId(), object.objectClass(), object.timestampNanos(),
                object.box(), count, status);
    }

    private record Fit(double slope, double rms) { }
}
