package kz.zholsafe.tracking;

/** REMOVED tracks are discarded and never published; LOST boxes are stale observations. */
public enum TrackState {
    TENTATIVE, CONFIRMED, LOST, REMOVED
}
