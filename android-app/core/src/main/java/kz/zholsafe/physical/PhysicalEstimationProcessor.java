package kz.zholsafe.physical;

import kz.zholsafe.config.PhysicalEstimationConfig;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.pipeline.TrackingSnapshot;
import kz.zholsafe.pipeline.TrajectorySnapshot;
import kz.zholsafe.tracking.TrackObservation;
import kz.zholsafe.tracking.TrackState;
import kz.zholsafe.tracking.TrackView;
import kz.zholsafe.tracking.TrackedObject;
import kz.zholsafe.trajectory.ObjectTrajectory;
import kz.zholsafe.trajectory.TrajectoryStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stage 4.1 single-threaded state owner. Consumes ONLY current immutable Stage 3/4.0 snapshots,
 * never frame data or a processing clock. An unavailable upstream result clears metric histories
 * (but does not count as an empty road observation). Successful empty frames clear tracks too.
 * Create a fresh instance, or call reset(), when the source/camera changes.
 */
public final class PhysicalEstimationProcessor {
    private final CameraCalibration calibration; // null => deliberately no physical ranging
    private final PhysicalEstimationConfig config;
    private final PhysicalDistanceEstimator distanceEstimator;
    private final MetricRangeRateEstimator rateEstimator = new MetricRangeRateEstimator();
    private final PhysicalTtcPort ttcEstimator = new PhysicalTtcEstimator();
    private final Map<Integer, ArrayDeque<MetricDistanceSample>> histories = new HashMap<>();
    private final Map<Integer, ObjectClass> historyClasses = new HashMap<>();
    private long lastFrameTimestampNanos;

    public PhysicalEstimationProcessor(CameraCalibration calibration, Map<ObjectClass, ObjectSizePrior> priors,
                                       PhysicalEstimationConfig config) {
        this.calibration = calibration;
        this.config = Objects.requireNonNull(config, "config");
        this.distanceEstimator = new ConservativeDistanceEstimator(priors);
    }

    public static PhysicalEstimationProcessor unavailableByDefault() {
        return new PhysicalEstimationProcessor(null, Map.of(), PhysicalEstimationConfig.defaults());
    }

    /** Clears all prior tracks and timestamp lineage on an explicit capture/source restart. */
    public void reset() {
        clearHistories();
        lastFrameTimestampNanos = 0L;
    }

    private void clearHistories() {
        histories.clear();
        historyClasses.clear();
    }

    /** Immutable defensive copy for diagnostics/tests; never exposes a mutable history. */
    public List<MetricDistanceSample> historyForTrack(int id) {
        ArrayDeque<MetricDistanceSample> history = histories.get(id);
        return history == null ? List.of() : List.copyOf(history);
    }

    public int retainedTrackCount() { return histories.size(); }

    public PhysicalEstimationSnapshot analyze(TrackingSnapshot tracking, TrajectorySnapshot trajectory) {
        Objects.requireNonNull(tracking, "tracking");
        Objects.requireNonNull(trajectory, "trajectory");
        long ts = tracking.frameTimestampNanos();
        if (!tracking.available()) {
            clearHistories();
            PhysicalEstimationSnapshot.Status status = tracking.status() == TrackingSnapshot.Status.NOT_STARTED
                    ? PhysicalEstimationSnapshot.Status.NOT_STARTED
                    : PhysicalEstimationSnapshot.Status.TRACKING_UNAVAILABLE;
            return unavailable(ts, status, tracking, trajectory);
        }
        if (!trajectory.available()) {
            clearHistories();
            return unavailable(ts, PhysicalEstimationSnapshot.Status.TRAJECTORY_UNAVAILABLE, tracking, trajectory);
        }
        if (ts <= lastFrameTimestampNanos || ts != trajectory.frameTimestampNanos()
                || tracking.uprightWidth() != trajectory.uprightWidth()
                || tracking.uprightHeight() != trajectory.uprightHeight()) {
            clearHistories();
            return unavailable(ts, PhysicalEstimationSnapshot.Status.INVALID_TIMESTAMP, tracking, trajectory);
        }
        // Preflight all identities and source times before mutating ANY history.
        Map<Integer, ObjectTrajectory> paths = new HashMap<>();
        for (ObjectTrajectory path : trajectory.objects()) {
            if (paths.putIfAbsent(path.trackId(), path) != null) {
                clearHistories();
                return unavailable(ts, PhysicalEstimationSnapshot.Status.ESTIMATOR_ERROR, tracking, trajectory);
            }
        }
        Set<Integer> currentIds = new HashSet<>();
        Set<Integer> allIds = new HashSet<>();
        for (TrackView view : tracking.tracks()) {
            TrackedObject object = view.object();
            ObjectTrajectory path = paths.get(object.trackId());
            if (!allIds.add(object.trackId()) || path == null || path.objectClass() != object.objectClass()
                    || !path.box().equals(object.box())) {
                clearHistories();
                return unavailable(ts, PhysicalEstimationSnapshot.Status.ESTIMATOR_ERROR, tracking, trajectory);
            }
            boolean current = view.state() == TrackState.CONFIRMED && view.missedFrames() == 0;
            if (current) {
                currentIds.add(object.trackId());
                if (path.status() == TrajectoryStatus.INVALID_TIMESTAMPS
                        || path.status() == TrajectoryStatus.TRACK_NOT_CURRENT) {
                    clearHistories();
                    return unavailable(ts, PhysicalEstimationSnapshot.Status.INVALID_TIMESTAMP, tracking, trajectory);
                }
                if (object.timestampNanos() != ts || path.timestampNanos() != ts
                        || !validObservationHistory(view.history(), ts, object.box())) {
                    clearHistories();
                    return unavailable(ts, PhysicalEstimationSnapshot.Status.INVALID_TIMESTAMP, tracking, trajectory);
                }
            } else if (path.available()) {
                clearHistories();
                return unavailable(ts, PhysicalEstimationSnapshot.Status.ESTIMATOR_ERROR, tracking, trajectory);
            }
        }
        if (allIds.size() != paths.size() || currentIds.size() > config.maxTrackedHistories()) {
            clearHistories();
            return unavailable(ts, PhysicalEstimationSnapshot.Status.ESTIMATOR_ERROR, tracking, trajectory);
        }
        histories.keySet().removeIf(id -> !currentIds.contains(id));
        historyClasses.keySet().removeIf(id -> !currentIds.contains(id));
        List<PhysicalObjectEstimate> result = new ArrayList<>(tracking.tracks().size());
        for (TrackView view : tracking.tracks()) {
            TrackedObject track = view.object();
            int id = track.trackId();
            if (!currentIds.contains(id)) {
                result.add(new PhysicalObjectEstimate(id, track.objectClass(), view.state(), ts,
                        DistanceEstimate.unavailable(ts, PhysicalReason.TRACK_NOT_CURRENT),
                        RangeRateEstimate.unavailable(ts, PhysicalReason.TRACK_NOT_CURRENT, 0),
                        TtcEstimate.unavailable(ts, PhysicalReason.TRACK_NOT_CURRENT),
                        TtcEstimate.unavailable(ts, PhysicalReason.TRACK_NOT_CURRENT),
                        TtcEstimate.unavailable(ts, PhysicalReason.TRACK_NOT_CURRENT)));
                continue;
            }
            DistanceEstimate distance = distanceEstimator.estimate(track, tracking.uprightWidth(),
                    tracking.uprightHeight(), calibration, config);
            if (distance.timestampNanos() != ts) {
                clearHistories();
                return unavailable(ts, PhysicalEstimationSnapshot.Status.INVALID_TIMESTAMP, tracking, trajectory);
            }
            RangeRateEstimate rate;
            if (!distance.available() || !distance.quality().atLeast(config.minimumDistanceQualityForRate())) {
                histories.remove(id); // Never bridge a low-quality or missing sample.
                historyClasses.remove(id);
                rate = RangeRateEstimate.unavailable(ts,
                        distance.available() ? PhysicalReason.LOW_QUALITY : distance.reason(), 0);
            } else {
                ArrayDeque<MetricDistanceSample> history = histories.computeIfAbsent(id, k -> new ArrayDeque<>());
                if (!history.isEmpty() && (!Objects.equals(historyClasses.get(id), track.objectClass())
                        || !compatibleMethods(history.getLast().method(), distance.method())
                        || view.history().stream().noneMatch(obs -> obs.timestampNanos()
                                == history.getLast().timestampNanos()))) {
                    history.clear(); // recreated ID, class change or change of ranging method
                }
                historyClasses.put(id, track.objectClass());
                if (!history.isEmpty()) {
                    double gap;
                    try {
                        gap = Math.subtractExact(ts, history.getLast().timestampNanos()) / 1e9;
                    } catch (ArithmeticException ex) {
                        gap = Double.POSITIVE_INFINITY;
                    }
                    if (gap <= 0d || gap > config.maximumMetricSampleGapSeconds()) history.clear();
                }
                history.addLast(new MetricDistanceSample(id, ts, distance.meters(), distance.quality(), distance.method()));
                while (history.size() > config.maximumMetricSamples()) history.removeFirst();
                rate = rateEstimator.fit(List.copyOf(history), config);
            }
            TtcEstimate metric = ttcEstimator.metric(distance, rate, config);
            TtcEstimate optical = ttcEstimator.imageScale(paths.get(id), ts, config);
            result.add(new PhysicalObjectEstimate(id, track.objectClass(), view.state(), ts, distance,
                    rate, metric, optical, ttcEstimator.selected(metric, optical, config)));
        }
        lastFrameTimestampNanos = ts;
        return new PhysicalEstimationSnapshot(ts, tracking.uprightWidth(), tracking.uprightHeight(),
                PhysicalEstimationSnapshot.Status.READY, tracking.status(), trajectory.status(), result);
    }

    private static boolean compatibleMethods(DistanceMethod a, DistanceMethod b) {
        // Cross-checked ground is still ground; object-height depth has a different error model.
        return (a == DistanceMethod.OBJECT_SIZE) == (b == DistanceMethod.OBJECT_SIZE);
    }

    private static boolean validObservationHistory(List<TrackObservation> history, long currentTs,
                                                   kz.zholsafe.model.BoundingBox currentBox) {
        if (history.isEmpty() || history.get(history.size() - 1).timestampNanos() != currentTs
                || !history.get(history.size() - 1).box().equals(currentBox)) return false;
        long last = 0L;
        for (TrackObservation obs : history) {
            if (obs.timestampNanos() <= last) return false;
            last = obs.timestampNanos();
        }
        return true;
    }

    private static PhysicalEstimationSnapshot unavailable(long ts, PhysicalEstimationSnapshot.Status status,
                                                           TrackingSnapshot tracking, TrajectorySnapshot trajectory) {
        return PhysicalEstimationSnapshot.unavailable(ts, status, tracking.status(), trajectory.status());
    }
}
