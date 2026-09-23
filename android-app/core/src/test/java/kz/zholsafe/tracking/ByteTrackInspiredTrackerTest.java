package kz.zholsafe.tracking;

import kz.zholsafe.config.TrackingConfig;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ByteTrackInspiredTrackerTest {
    private static final BoundingBox A = new BoundingBox(0, 0, 20, 20);
    private static final BoundingBox B = new BoundingBox(100, 0, 120, 20);
    private static TrackingConfig cfg(int coast, int hits, int history, int capacity) {
        return new TrackingConfig(0.3f, coast, hits, history, .3f, .7f, .4f, .6f, .3f, capacity);
    }
    private static Detection d(ObjectClass type, BoundingBox b, float confidence, long ts) {
        return new Detection(17, type, confidence, b, ts);
    }
    private static Detection horse(BoundingBox b, float confidence, long ts) {
        return d(ObjectClass.HORSE, b, confidence, ts);
    }
    private static TrackView only(ByteTrackInspiredTracker t) {
        assertEquals(1, t.views().size());
        return t.views().get(0);
    }

    @Test void singleStableObjectTentativeThenConfirmed() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(2, 2, 4, 32));
        t.update(List.of(horse(A, .85f, 1)), 1);
        assertEquals(TrackState.TENTATIVE, only(t).state());
        assertEquals(1, only(t).object().trackId());
        t.update(List.of(horse(new BoundingBox(1, 0, 21, 20), .9f, 2)), 2);
        TrackView v = only(t);
        assertEquals(TrackState.CONFIRMED, v.state());
        assertEquals(1, v.object().trackId());
        assertEquals(2, v.hits());
        assertEquals(2, v.object().ageFrames());
        assertFalse(v.object().distanceEstimated());
        assertFalse(v.object().ttcEstimated());
        assertEquals(MovementClass.UNKNOWN, v.object().movement());
        assertFalse(v.object().inDrivingCorridor());
    }

    @Test void twoObjectsAndNewEntrantPreserveIds() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(2, 2, 4, 32));
        t.update(List.of(horse(A, .9f, 1), d(ObjectClass.COW, B, .8f, 1)), 1);
        t.update(List.of(horse(A, .9f, 2), d(ObjectClass.COW, B, .8f, 2)), 2);
        assertEquals(List.of(1, 2), t.views().stream().map(v -> v.object().trackId()).toList());
        t.update(List.of(horse(A, .9f, 3), d(ObjectClass.COW, B, .8f, 3),
                d(ObjectClass.PERSON, new BoundingBox(200, 0, 220, 20), .9f, 3)), 3);
        assertEquals(List.of(1, 2, 3), t.views().stream().map(v -> v.object().trackId()).toList());
        assertEquals(TrackState.CONFIRMED, t.views().get(0).state());
    }

    @Test void temporaryMissLostAndRecoveredByHigh() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(2, 2, 3, 32));
        t.update(List.of(horse(A, .9f, 1)), 1);
        t.update(List.of(horse(A, .9f, 2)), 2);
        t.update(List.of(), 3);
        assertEquals(TrackState.LOST, only(t).state());
        assertEquals(1, only(t).missedFrames());
        assertEquals(2, only(t).object().timestampNanos(), "lost box keeps last observation timestamp");
        t.update(List.of(), 4);
        assertEquals(2, only(t).missedFrames());
        t.update(List.of(horse(A, .9f, 5)), 5);
        assertEquals(TrackState.CONFIRMED, only(t).state());
        assertEquals(1, only(t).object().trackId());
        assertEquals(0, only(t).missedFrames());
    }

    @Test void expirationNeverReusesIdsEvenAfterReset() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(1, 2, 3, 32));
        t.update(List.of(horse(A, .9f, 1)), 1);
        t.update(List.of(horse(A, .9f, 2)), 2);
        t.update(List.of(), 3);
        t.update(List.of(), 4);
        assertTrue(t.views().isEmpty());
        t.update(List.of(horse(A, .9f, 5)), 5);
        assertEquals(2, only(t).object().trackId());
        t.reset();
        assertTrue(t.views().isEmpty());
        t.update(List.of(horse(A, .9f, 1)), 1); // reset permits new source clock domain
        assertEquals(3, only(t).object().trackId());
    }

    @Test void classMismatchAndUnknownCannotBridge() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(1, 2, 3, 32));
        t.update(List.of(horse(A, .9f, 1)), 1);
        t.update(List.of(horse(A, .9f, 2)), 2);
        t.update(List.of(d(ObjectClass.PERSON, A, .9f, 3), d(ObjectClass.UNKNOWN, A, .9f, 3)), 3);
        assertEquals(TrackState.LOST, t.views().get(0).state());
        assertEquals(ObjectClass.HORSE, t.views().get(0).object().objectClass());
        assertEquals(List.of(1, 2, 3), t.views().stream().map(v -> v.object().trackId()).toList());
        t.update(List.of(d(ObjectClass.PERSON, A, .9f, 4)), 4);
        assertEquals(2, t.views().get(0).object().trackId());
        assertEquals(TrackState.CONFIRMED, t.views().get(0).state());
    }

    @Test void lowConfidenceRecoveryButNoNewOrTentativeConfirmation() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(2, 2, 3, 32));
        t.update(List.of(horse(A, .8f, 1)), 1);
        t.update(List.of(horse(A, .4f, 2)), 2);
        assertTrue(t.views().isEmpty(), "tentative track cannot be confirmed by a low score");
        t.update(List.of(horse(A, .4f, 3)), 3);
        assertTrue(t.views().isEmpty(), "isolated low score never starts a track");
        t.update(List.of(horse(A, .8f, 4)), 4);
        t.update(List.of(horse(A, .8f, 5)), 5);
        int id = only(t).object().trackId();
        t.update(List.of(), 6);
        t.update(List.of(horse(A, .4f, 7)), 7);
        assertEquals(id, only(t).object().trackId());
        assertEquals(TrackState.CONFIRMED, only(t).state());
        assertEquals(.4f, only(t).object().confidence());
        t.update(List.of(horse(A, .2f, 8)), 8); // below low threshold = miss
        assertEquals(TrackState.LOST, only(t).state());
    }

    @Test void matchTieBreaksByTrackIdThenInputIndex() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(2, 1, 4, 32));
        t.update(List.of(horse(A, .9f, 1), horse(A, .9f, 1)), 1);
        t.update(List.of(horse(new BoundingBox(1, 0, 21, 20), .9f, 2),
                horse(new BoundingBox(2, 0, 22, 20), .9f, 2)), 2);
        assertEquals(1f, t.views().get(0).object().box().x1());
        assertEquals(2f, t.views().get(1).object().box().x1());
        t.update(List.of(horse(new BoundingBox(3, 0, 23, 20), .9f, 3),
                horse(new BoundingBox(3, 0, 23, 20), .8f, 3)), 3);
        // Different IoUs determine the first pairing; each detection can match at most one track.
        assertEquals(2, t.views().size());
        assertEquals(3, t.views().get(0).hits());
        assertEquals(3, t.views().get(1).hits());
    }

    @Test void exactIouTiePrefersEarlierDetectionIndex() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(1, 1, 3, 32));
        t.update(List.of(horse(A, .9f, 1)), 1);
        // Both identical geometry/IoU. Confidence does not override the stated IoU/index tie.
        t.update(List.of(horse(A, .7f, 2), horse(A, .95f, 2)), 2);
        assertEquals(.7f, t.views().get(0).object().confidence());
        assertEquals(2, t.views().size()); // second unmatched high detection starts a new track
    }

    @Test void timestampsAndBoxesRejectInvalidInputWithoutMutation() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(2, 1, 3, 32));
        assertThrows(IllegalArgumentException.class, () -> t.update(List.of(), 0));
        t.update(List.of(horse(A, .8f, 10)), 10);
        assertThrows(IllegalArgumentException.class, () -> t.update(List.of(horse(A, .9f, 10)), 10));
        assertThrows(IllegalArgumentException.class, () -> t.update(List.of(horse(A, .9f, 9)), 9));
        assertThrows(IllegalArgumentException.class, () -> t.update(List.of(horse(A, .9f, 12)), 11));
        assertThrows(IllegalArgumentException.class,
                () -> t.update(List.of(horse(new BoundingBox(0, 0, Float.POSITIVE_INFINITY, 20), .9f, 11)), 11));
        assertThrows(IllegalArgumentException.class,
                () -> t.update(List.of(horse(new BoundingBox(0, 0, 0, 20), .9f, 11)), 11));
        assertEquals(1, only(t).hits());
        t.update(List.of(horse(A, .9f, 11)), 11);
        assertEquals(2, only(t).hits());
    }

    @Test void iouDisjointAndTouchingDoNotMatchAndThresholdIsInclusive() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(1, 1, 3, 32));
        t.update(List.of(horse(A, .9f, 1)), 1);
        t.update(List.of(horse(new BoundingBox(20, 0, 40, 20), .9f, 2)), 2);
        assertEquals(2, t.views().size()); // edge contact IoU = 0
        assertEquals(TrackState.LOST, t.views().get(0).state());
        t.update(List.of(horse(new BoundingBox(100, 0, 120, 20), .9f, 3)), 3);
        assertEquals(2, t.views().size()); // old expired, next lost, new tentative/confirmed
        assertEquals(3, t.views().get(1).object().trackId());
        ByteTrackInspiredTracker overlap = new ByteTrackInspiredTracker(
                new TrackingConfig(1f, 1, 1, 3, .3f, .7f, .4f, .6f, .3f, 32));
        overlap.update(List.of(horse(A, .9f, 1)), 1);
        // Identical boxes: IoU = 1 exactly, accepted at the inclusive threshold.
        overlap.update(List.of(horse(A, .9f, 2)), 2);
        assertEquals(1, only(overlap).object().trackId());
    }

    @Test void historyImmutableBoundedAndNoPhysicalEstimates() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(1, 1, 2, 32));
        t.update(List.of(horse(A, .9f, 1)), 1);
        List<TrackView> old = t.views();
        assertThrows(UnsupportedOperationException.class, () -> old.clear());
        for (int i = 2; i <= 10; i++) t.update(List.of(horse(A, .9f, i)), i);
        TrackView current = only(t);
        assertEquals(2, current.history().size());
        assertEquals(9L, current.history().get(0).timestampNanos());
        assertEquals(2, current.object().positionHistory().size());
        assertEquals(1, old.get(0).history().size(), "old view is immutable, not a live handle");
        assertThrows(UnsupportedOperationException.class, () -> current.history().clear());
        assertThrows(UnsupportedOperationException.class, () -> current.object().positionHistory().clear());
        assertFalse(current.object().estimatedDistance().available());
        assertFalse(current.object().estimatedTtc().available());
    }

    @Test void capacityKeepsHighestScoresThenEarlierInputIndex() {
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(cfg(1, 1, 2, 2));
        t.update(List.of(horse(A, .7f, 1), horse(B, .9f, 1),
                horse(new BoundingBox(200, 0, 220, 20), .9f, 1),
                horse(new BoundingBox(300, 0, 320, 20), .9f, 1)), 1);
        assertEquals(2, t.views().size());
        assertEquals(B, t.views().get(0).object().box()); // earlier index wins .9 tie
        assertEquals(200f, t.views().get(1).object().box().x1());
    }

    @Test void stressTwentyPlusObjectsCapacityAndDeterminism() {
        TrackingConfig c = cfg(2, 2, 3, 24);
        ByteTrackInspiredTracker t = new ByteTrackInspiredTracker(c);
        for (int frame = 1; frame <= 8; frame++) {
            List<Detection> dets = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                dets.add(horse(new BoundingBox(i * 40, 0, i * 40 + 20, 20), .9f, frame));
            }
            // 25th weak track must not consume capacity; selection/work state bounded at 24.
            dets.add(horse(new BoundingBox(3000, 0, 3020, 20), .4f, frame));
            t.update(dets, frame);
            assertEquals(24, t.views().size());
            Set<Integer> ids = new HashSet<>();
            for (TrackView v : t.views()) {
                assertTrue(ids.add(v.object().trackId()));
                assertTrue(v.history().size() <= c.historyLength());
                assertTrue(v.object().positionHistory().size() <= c.historyLength());
                assertTrue(v.object().trackId() <= 24);
            }
        }
        assertEquals(24, t.views().stream().filter(v -> v.state() == TrackState.CONFIRMED).count());
        ByteTrackInspiredTracker other = new ByteTrackInspiredTracker(c);
        for (int frame = 1; frame <= 8; frame++) {
            List<Detection> dets = new ArrayList<>();
            for (int i = 0; i < 24; i++) dets.add(horse(new BoundingBox(i * 40, 0, i * 40 + 20, 20), .9f, frame));
            other.update(dets, frame);
        }
        assertEquals(t.views(), other.views());
    }
}
