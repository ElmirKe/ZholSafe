package kz.zholsafe.tracking;

/** Coarse motion classification of a tracked object relative to the ego vehicle's camera. */
public enum MovementClass {
    /** Not enough history to classify. */
    UNKNOWN,
    STATIONARY,
    /** Moving laterally toward the driving corridor. */
    APPROACHING_CORRIDOR,
    /** Moving laterally away from the driving corridor. */
    LEAVING_CORRIDOR,
    /** Apparent size growing quickly — closing distance. */
    CLOSING,
    /** Apparent size shrinking — receding. */
    RECEDING,
    /** Already inside the corridor and moving along it. */
    IN_CORRIDOR
}
