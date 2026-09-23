package kz.zholsafe.driver;

/**
 * ENGINEERING state of mouth/yawn-LIKE evidence. {@link #YAWN_LIKE} requires the mouth-open score
 * to persist above the configured threshold for the configured duration; a single open-mouth
 * frame is only {@link #MOUTH_OPEN}. This is NOT a medical yawn or fatigue diagnosis — the
 * "yawn-like" name is deliberate.
 */
public enum YawnLikeState {
    /** Mouth evaluable and below the open threshold. */
    NONE,
    /** Mouth open above threshold but not yet persistent. */
    MOUTH_OPEN,
    /** Mouth open persisted at least the configured minimum duration (EXPERIMENTAL threshold). */
    YAWN_LIKE,
    /** Mouth evidence unavailable (no face, no mouth landmarks, or low confidence). */
    UNAVAILABLE
}
