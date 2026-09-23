package kz.zholsafe.tracking;

import kz.zholsafe.config.TrackingConfig;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.Estimate;
import kz.zholsafe.model.ObjectClass;
import kz.zholsafe.model.Point2D;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

/**
 * ByteTrack-inspired, NOT official ByteTrack. Single-threaded deterministic class-exact greedy
 * IoU tracker. No motion prediction, Kalman filter, re-ID, physical estimates or image retention.
 * Call only after successful detector execution; failure freezes state and is reported separately
 * by the pipeline. See docs/STAGE3_TRACKING.md for association/lifecycle/capacity semantics.
 */
public final class ByteTrackInspiredTracker implements ObjectTracker {
    private final TrackingConfig config;
    private final TreeMap<Integer, Track> tracks = new TreeMap<>();
    private int nextId = 1;
    private long lastTimestamp;

    public ByteTrackInspiredTracker(TrackingConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public List<TrackedObject> update(List<Detection> detections, long timestampNanos) {
        Objects.requireNonNull(detections, "detections");
        if (timestampNanos <= 0 || timestampNanos <= lastTimestamp) {
            throw new IllegalArgumentException("source timestamp must be positive and strictly increasing");
        }
        // Validate BEFORE any state mutation. Input order breaks exact-IoU ties; cap the working
        // set by descending confidence then input index so internal matching memory stays bounded.
        Comparator<IndexedDetection> worstFirst = Comparator
                .<IndexedDetection>comparingDouble(v -> v.detection.confidence())
                .thenComparing(Comparator.comparingInt((IndexedDetection v) -> v.index).reversed());
        PriorityQueue<IndexedDetection> best = new PriorityQueue<>(worstFirst);
        for (int i = 0; i < detections.size(); i++) {
            Detection d = Objects.requireNonNull(detections.get(i), "detection");
            if (d.timestampNanos() != timestampNanos) {
                throw new IllegalArgumentException("detection timestamp differs from frame timestamp");
            }
            BoundingBox b = d.box();
            if (!Float.isFinite(b.x1()) || !Float.isFinite(b.y1()) || !Float.isFinite(b.x2())
                    || !Float.isFinite(b.y2()) || b.x2() <= b.x1() || b.y2() <= b.y1()) {
                throw new IllegalArgumentException("tracking requires finite nondegenerate boxes");
            }
            if (d.confidence() >= config.lowConfidenceThreshold()) {
                IndexedDetection item = new IndexedDetection(i, d);
                if (best.size() < config.maxActiveTracks()) best.add(item);
                else if (worstFirst.compare(item, best.peek()) > 0) {
                    best.remove();
                    best.add(item);
                }
            }
        }
        List<IndexedDetection> selected = new ArrayList<>(best);
        selected.sort(Comparator.comparingInt(v -> v.index));
        List<IndexedDetection> high = new ArrayList<>();
        List<IndexedDetection> low = new ArrayList<>();
        for (IndexedDetection d : selected) {
            (d.detection.confidence() >= config.highConfidenceThreshold() ? high : low).add(d);
        }

        for (Track t : tracks.values()) t.age = increment(t.age);
        Set<Integer> matchedTracks = new HashSet<>();
        Set<Integer> matchedDetections = new HashSet<>();
        associate(high, false, matchedTracks, matchedDetections);
        associate(low, true, matchedTracks, matchedDetections);

        for (Track t : tracks.values()) {
            if (matchedTracks.contains(t.id)) continue;
            // Even a pathological maxCoastFrames=Integer.MAX_VALUE cannot wrap into a
            // negative miss count and keep a stale track indefinitely.
            boolean exhaustedCounter = t.missed == Integer.MAX_VALUE;
            t.missed = increment(t.missed);
            if (t.state == TrackState.TENTATIVE || exhaustedCounter || t.missed > config.maxCoastFrames()) {
                t.state = TrackState.REMOVED;
            } else {
                t.state = TrackState.LOST;
            }
        }
        tracks.values().removeIf(t -> t.state == TrackState.REMOVED);
        for (IndexedDetection d : high) {
            if (matchedDetections.contains(d.index) || tracks.size() >= config.maxActiveTracks()) continue;
            if (nextId == Integer.MAX_VALUE) throw new IllegalStateException("track ID space exhausted");
            Track t = new Track(nextId++, d.detection);
            tracks.put(t.id, t);
        }
        lastTimestamp = timestampNanos;
        return views().stream().map(TrackView::object).toList();
    }

    private void associate(List<IndexedDetection> candidates, boolean recovery,
                           Set<Integer> matchedTracks, Set<Integer> matchedDetections) {
        List<Match> matches = new ArrayList<>();
        for (Track track : tracks.values()) {
            if (matchedTracks.contains(track.id) || (recovery && track.state == TrackState.TENTATIVE)) continue;
            for (IndexedDetection candidate : candidates) {
                Detection d = candidate.detection;
                // UNKNOWN matches UNKNOWN only: never bridge an unidentified object to a known class.
                if (track.objectClass != d.objectClass()) continue;
                double overlap = iou(track.box, d.box());
                if (overlap >= config.iouMatchThreshold()) {
                    matches.add(new Match(track.id, candidate.index, d, overlap));
                }
            }
        }
        // Highest IoU wins; exact ties: smaller track ID, then earlier input detection index.
        matches.sort(Comparator.<Match>comparingDouble(m -> m.iou).reversed()
                .thenComparingInt(m -> m.trackId).thenComparingInt(m -> m.detectionIndex));
        for (Match match : matches) {
            if (matchedTracks.add(match.trackId)) {
                if (matchedDetections.add(match.detectionIndex)) {
                    tracks.get(match.trackId).observe(match.detection, !recovery);
                } else {
                    matchedTracks.remove(match.trackId);
                }
            }
        }
    }

    private static double iou(BoundingBox a, BoundingBox b) {
        double iw = Math.max(0d, Math.min((double) a.x2(), b.x2()) - Math.max((double) a.x1(), b.x1()));
        double ih = Math.max(0d, Math.min((double) a.y2(), b.y2()) - Math.max((double) a.y1(), b.y1()));
        double inter = iw * ih;
        double areaA = ((double) a.x2() - a.x1()) * ((double) a.y2() - a.y1());
        double areaB = ((double) b.x2() - b.x1()) * ((double) b.y2() - b.y1());
        return inter / (areaA + areaB - inter);
    }

    private static int increment(int n) { return n == Integer.MAX_VALUE ? n : n + 1; }

    /** Immutable, ID-ordered output including coasting LOST tracks (with stale boxes). */
    public List<TrackView> views() {
        return tracks.values().stream().map(Track::view).toList();
    }

    /** Clear active state and clock domain; IDs never reset or get reused in this tracker instance. */
    @Override
    public void reset() {
        tracks.clear();
        lastTimestamp = 0;
    }

    private record IndexedDetection(int index, Detection detection) { }
    private record Match(int trackId, int detectionIndex, Detection detection, double iou) { }

    private final class Track {
        final int id;
        final ObjectClass objectClass;
        final ArrayDeque<TrackObservation> history = new ArrayDeque<>();
        BoundingBox box;
        float confidence;
        long lastSeen;
        int age = 1;
        int hits = 1;
        int consecutiveHighHits = 1;
        int missed;
        TrackState state;

        Track(int id, Detection d) {
            this.id = id;
            this.objectClass = d.objectClass();
            this.state = config.minHitsToConfirm() == 1 ? TrackState.CONFIRMED : TrackState.TENTATIVE;
            record(d);
        }

        void observe(Detection d, boolean high) {
            hits = increment(hits);
            missed = 0;
            if (state == TrackState.TENTATIVE) {
                consecutiveHighHits = high ? increment(consecutiveHighHits) : 0;
                if (consecutiveHighHits >= config.minHitsToConfirm()) state = TrackState.CONFIRMED;
            } else {
                state = TrackState.CONFIRMED;
            }
            record(d);
        }

        void record(Detection d) {
            box = d.box();
            confidence = d.confidence();
            lastSeen = d.timestampNanos();
            history.addLast(new TrackObservation(lastSeen, box, confidence));
            if (history.size() > config.historyLength()) history.removeFirst();
        }

        TrackView view() {
            List<TrackObservation> observations = List.copyOf(history);
            List<Point2D> centers = observations.stream().map(TrackObservation::center).toList();
            TrackedObject object = new TrackedObject(id, objectClass, confidence, box, centers,
                    MovementClass.UNKNOWN, Estimate.unavailable(), Estimate.unavailable(), false,
                    age, lastSeen);
            return new TrackView(object, state, hits, missed, observations);
        }
    }
}
