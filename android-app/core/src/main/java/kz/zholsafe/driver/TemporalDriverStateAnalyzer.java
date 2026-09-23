package kz.zholsafe.driver;

import kz.zholsafe.config.DriverGuardConfig;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;

/**
 * Stage 4.3 temporal DriverGuard core. Consumes {@link DriverObservation}s on a single thread and
 * maintains BOUNDED scalar memory only: one ring of (source-timestamp, eye-class) entries for the
 * PERCLOS-like window — evicted by time and by the hard {@code maxObservations} cap — plus a
 * handful of run-start timestamps. No Frame, image, bitmap or tensor is ever stored.
 *
 * <h2>Source-time discipline</h2>
 * Every duration uses {@link DriverObservation#timestampNanos()}. Duplicate/reversed source
 * timestamps are explicitly REJECTED ({@link DriverState.TimestampRejection}) and leave temporal
 * state untouched. A source-time gap beyond {@code maximumObservationGapSeconds} breaks every
 * continuity run: neither OPEN nor CLOSED is assumed across it, and the gap is excluded from the
 * PERCLOS window.
 *
 * <h2>Eye evidence</h2>
 * An observation yields eye evidence only when the face is detected, both eye-openness values are
 * available and confidence meets the configured minimum; otherwise the eye state is UNKNOWN and
 * the observation feeds neither the closure run nor the PERCLOS numerator/denominator
 * ("missing != open, missing != closed"). Both-eyes-closed uses min(left, right) openness.
 *
 * <h2>Not a diagnosis</h2>
 * The produced {@link DriverState} is a bundle of temporal engineering evidence with EXPERIMENTAL
 * thresholds. It never claims drowsiness or microsleep.
 */
public final class TemporalDriverStateAnalyzer implements DriverStateAnalyzer {

    // Eye classification retained per accepted observation (EYE_INVALID = not evaluable).
    private static final int EYE_OPEN = 0;
    private static final int EYE_PARTIAL = 1;
    private static final int EYE_CLOSED = 2;
    private static final int EYE_INVALID = 3;

    /** Minimal ring entry (two words): the only per-observation state ever retained. */
    private record Sample(long timestampNanos, int eyeClass) { }

    private final DriverGuardConfig config;
    private final long perclosWindowNanos;
    private final long maxGapNanos;
    private final long minYawnNanos;

    private final Deque<Sample> window = new ArrayDeque<>();

    private boolean seen;
    private long lastAcceptedTs;
    private int lastEyeClass = EYE_INVALID;
    private boolean prevFaceMissing;
    private boolean prevEyeUnavailable;
    private boolean prevMouthOpen;
    private boolean prevHeadAway;

    private long closureStartTs = -1L;
    private long faceLossStartTs = -1L;
    private long eyeUnavailableStartTs = -1L;
    private long mouthOpenStartTs = -1L;
    private long headAwayStartTs = -1L;

    private DriverState current = DriverState.unavailable(0L);

    public TemporalDriverStateAnalyzer(DriverGuardConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.perclosWindowNanos = nanos(config.perclosWindowSeconds());
        this.maxGapNanos = nanos(config.maximumObservationGapSeconds());
        this.minYawnNanos = nanos(config.minimumYawnDurationSeconds());
    }

    @Override
    public DriverState update(DriverObservation observation) {
        Objects.requireNonNull(observation, "observation");
        long ts = observation.timestampNanos();

        // ---- monotonicity: duplicate/reversed source timestamps are explicitly rejected ----
        if (seen && ts <= lastAcceptedTs) {
            DriverState.TimestampRejection rejection = ts == lastAcceptedTs
                    ? DriverState.TimestampRejection.DUPLICATE_TIMESTAMP
                    : DriverState.TimestampRejection.REVERSED_TIMESTAMP;
            current = DriverState.withRejection(current, rejection);
            return current;
        }
        boolean gap = seen && (ts - lastAcceptedTs) > maxGapNanos;

        boolean face = observation.faceDetected();
        boolean trusted = face && observation.confidence() >= config.minimumObservationConfidence();

        // ---- qualitative eye state (UNKNOWN when not evaluable) ----
        int eyeClass = EYE_INVALID;
        EyeState eyeState = EyeState.UNKNOWN;
        if (trusted && observation.eyeOpennessAvailable()) {
            float openness = Math.min(observation.leftEyeOpenness(), observation.rightEyeOpenness());
            if (openness <= config.eyeClosedThreshold()) {
                eyeClass = EYE_CLOSED;
                eyeState = EyeState.CLOSED;
            } else if (openness <= config.eyePartiallyClosedThreshold()) {
                eyeClass = EYE_PARTIAL;
                eyeState = EyeState.PARTIALLY_CLOSED;
            } else {
                eyeClass = EYE_OPEN;
                eyeState = EyeState.OPEN;
            }
        }

        // ---- continuous closure run (source time; broken by UNKNOWN / OPEN / gap) ----
        long closureNanos = 0L;
        if (eyeState == EyeState.CLOSED) {
            if (!(lastEyeClass == EYE_CLOSED && !gap)) {
                closureStartTs = ts;
            }
            closureNanos = ts - closureStartTs;
        } else {
            closureStartTs = -1L;
        }

        // ---- face-loss run ----
        long faceLossNanos = 0L;
        if (!face) {
            if (!(prevFaceMissing && !gap)) {
                faceLossStartTs = ts;
            }
            faceLossNanos = ts - faceLossStartTs;
        } else {
            faceLossStartTs = -1L;
        }

        // ---- face visible but eyes not evaluable run ----
        boolean eyeUnavailable = face && eyeClass == EYE_INVALID;
        long eyeUnavailableNanos = 0L;
        if (eyeUnavailable) {
            if (!(prevEyeUnavailable && !gap)) {
                eyeUnavailableStartTs = ts;
            }
            eyeUnavailableNanos = ts - eyeUnavailableStartTs;
        } else {
            eyeUnavailableStartTs = -1L;
        }

        // ---- yawn-like run (mouth evidence only; UNKNOWN when not evaluable) ----
        boolean mouthTrusted = trusted && observation.mouthAvailable();
        boolean mouthOpen = mouthTrusted && observation.mouthOpenScore() >= config.mouthOpenThreshold();
        long mouthOpenNanos = 0L;
        if (mouthOpen) {
            if (!(prevMouthOpen && !gap)) {
                mouthOpenStartTs = ts;
            }
            mouthOpenNanos = ts - mouthOpenStartTs;
        } else {
            mouthOpenStartTs = -1L;
        }
        YawnLikeState yawn;
        if (!mouthTrusted) {
            yawn = YawnLikeState.UNAVAILABLE;
        } else if (mouthOpen && mouthOpenNanos >= minYawnNanos) {
            yawn = YawnLikeState.YAWN_LIKE;
        } else if (mouthOpen) {
            yawn = YawnLikeState.MOUTH_OPEN;
        } else {
            yawn = YawnLikeState.NONE;
        }

        // ---- qualitative head pose (UNKNOWN when not evaluable; DOWN wins over lateral) ----
        HeadPoseState headState = HeadPoseState.UNKNOWN;
        if (trusted && observation.headPose().available()) {
            HeadPose pose = observation.headPose();
            if (pose.pitchDeg() >= config.headDownPitchThresholdDegrees()) {
                headState = HeadPoseState.DOWN;
            } else if (pose.yawDeg() <= -config.headYawThresholdDegrees()) {
                headState = HeadPoseState.LEFT;
            } else if (pose.yawDeg() >= config.headYawThresholdDegrees()) {
                headState = HeadPoseState.RIGHT;
            } else {
                headState = HeadPoseState.FORWARD;
            }
        }
        boolean headAway = headState.isAway();
        long headAwayNanos = 0L;
        if (headAway) {
            if (!(prevHeadAway && !gap)) {
                headAwayStartTs = ts;
            }
            headAwayNanos = ts - headAwayStartTs;
        } else {
            headAwayStartTs = -1L;
        }

        // ---- bounded window for the PERCLOS-like metric ----
        window.addLast(new Sample(ts, eyeClass));
        evict(ts);
        PerclosValue perclos = computePerclos(ts);

        ObservationQuality quality = !face ? ObservationQuality.UNAVAILABLE
                : eyeClass == EYE_INVALID ? ObservationQuality.DEGRADED : ObservationQuality.GOOD;

        current = new DriverState(ts, face, eyeState, closureNanos, perclos, headState,
                headAwayNanos, observation.headPose(), yawn, mouthOpenNanos, faceLossNanos,
                eyeUnavailableNanos, quality, observation.confidence(),
                DriverState.TimestampRejection.NONE);

        seen = true;
        lastAcceptedTs = ts;
        lastEyeClass = eyeClass;
        prevFaceMissing = !face;
        prevEyeUnavailable = eyeUnavailable;
        prevMouthOpen = mouthOpen;
        prevHeadAway = headAway;
        return current;
    }

    @Override
    public DriverState current() {
        return current;
    }

    @Override
    public void reset() {
        window.clear();
        seen = false;
        lastAcceptedTs = 0L;
        lastEyeClass = EYE_INVALID;
        prevFaceMissing = false;
        prevEyeUnavailable = false;
        prevMouthOpen = false;
        prevHeadAway = false;
        closureStartTs = -1L;
        faceLossStartTs = -1L;
        eyeUnavailableStartTs = -1L;
        mouthOpenStartTs = -1L;
        headAwayStartTs = -1L;
        current = DriverState.unavailable(0L);
    }

    /**
     * Time-based eviction: keeps the newest sample at/before the window cutoff (its segment still
     * overlaps the window), drops everything older. Then applies the hard observation cap.
     */
    private void evict(long now) {
        long cutoff = now - perclosWindowNanos;
        while (window.size() >= 2 && secondTimestamp() <= cutoff) {
            window.pollFirst();
        }
        while (window.size() > config.maxObservations()) {
            window.pollFirst();
        }
    }

    private long secondTimestamp() {
        Iterator<Sample> it = window.iterator();
        it.next();
        return it.next().timestampNanos();
    }

    /**
     * Time-weighted, coverage-aware PERCLOS-like value over [now − window, now]. Segments between
     * consecutive samples are weighted by their SOURCE-TIME duration (never counted per frame).
     * Only segments whose start is a valid eye observation and whose span is within the maximum
     * gap enter the denominator; within those, only CLOSED segments enter the numerator.
     *
     * <p>Availability requires BOTH maturity and coverage, in one rule: the valid observed-eye
     * time inside the window must reach {@code minimumPerclosValidCoverage × configured window}.
     * This deliberately keeps the metric unavailable for young windows (a 1-second-old window
     * that is 100% "closed" is a blink/closure event, not a PERCLOS measurement), so the
     * windowed metric can never fight the continuous-closure timers during short episodes.
     * Insufficient coverage is reported as UNAVAILABLE — never as 0.
     */
    private PerclosValue computePerclos(long now) {
        long cutoff = now - perclosWindowNanos;
        long valid = 0L;
        long closed = 0L;
        boolean havePrev = false;
        long prevTs = 0L;
        int prevClass = EYE_INVALID;
        for (Sample sample : window) {
            if (havePrev) {
                long a = Math.max(prevTs, cutoff);
                long b = sample.timestampNanos();
                boolean segmentValid = prevClass != EYE_INVALID
                        && (sample.timestampNanos() - prevTs) <= maxGapNanos;
                if (segmentValid && b > a) {
                    valid += b - a;
                    if (prevClass == EYE_CLOSED) {
                        closed += b - a;
                    }
                }
            }
            prevTs = sample.timestampNanos();
            prevClass = sample.eyeClass();
            havePrev = true;
        }
        Sample first = window.peekFirst();
        long effectiveStart = Math.max(cutoff, first == null ? now : first.timestampNanos());
        long windowDuration = Math.max(0L, now - effectiveStart);
        long requiredValid = Math.round((double) config.minimumPerclosValidCoverage()
                * (double) perclosWindowNanos);
        if (valid <= 0L || valid < requiredValid) {
            return PerclosValue.unavailable(valid, windowDuration);
        }
        float value = (float) ((double) closed / (double) valid);
        return new PerclosValue(true, Math.min(1f, Math.max(0f, value)), valid, windowDuration);
    }

    private static long nanos(double seconds) {
        return Math.round(seconds * 1_000_000_000d);
    }
}
