package kz.zholsafe.risk;

import kz.zholsafe.config.DriverGuardConfig;
import kz.zholsafe.driver.DriverState;
import kz.zholsafe.driver.EyeState;
import kz.zholsafe.driver.HeadPoseState;
import kz.zholsafe.driver.ObservationQuality;
import kz.zholsafe.driver.YawnLikeState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 4.3 DRIVER-ONLY temporal risk policy. Deterministic rules over one immutable
 * {@link DriverState}. Road data (object classes, tracks, TTC, corridor) NEVER enters this
 * engine; persistence evidence comes only from the analyzer's source-time durations.
 *
 * <p>Levels are ENGINEERING severity (NORMAL &lt; CAUTION &lt; WARNING &lt; CRITICAL), not
 * probabilities and not a medical diagnosis:
 * <ul>
 *   <li><b>NORMAL</b>: valid observation, no concerning temporal evidence. A single short blink,
 *     a single open-mouth frame, a single missing-face frame or a brief head turn stays here.</li>
 *   <li><b>CAUTION</b>: weak/persistent attention or monitoring evidence: moderate closure or
 *     PERCLOS elevation, a yawn-like event, persistent lateral head-away, persistent face/eye
 *     visibility loss (monitoring evidence, distinct from fatigue evidence).</li>
 *   <li><b>WARNING</b>: strong prolonged closure, warning-level PERCLOS, or persistent
 *     looking-down.</li>
 *   <li><b>CRITICAL</b>: only STRONG SUSTAINED evidence — closure past the critical threshold, or
 *     warning-level closure together with warning-level PERCLOS. No single observation can ever
 *     reach CRITICAL.</li>
 * </ul>
 * All thresholds are EXPERIMENTAL demo values from {@link DriverGuardConfig} (see its javadoc);
 * the ~1.5 s WARNING closure threshold is an EXPERIMENTAL DEMO THRESHOLD, not a microsleep
 * definition.
 */
public final class DriverRiskEngine implements DriverRiskEvaluator {

    private final DriverGuardConfig config;
    private final long cautionClosureNanos;
    private final long warningClosureNanos;
    private final long criticalClosureNanos;
    private final long headAwayNanos;
    private final long persistentFaceLossNanos;
    private final long eyeVisibilityLostNanos;

    public DriverRiskEngine(DriverGuardConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.cautionClosureNanos = nanos(config.prolongedClosureCautionSeconds());
        this.warningClosureNanos = nanos(config.prolongedClosureWarningSeconds());
        this.criticalClosureNanos = nanos(config.prolongedClosureCriticalSeconds());
        this.headAwayNanos = nanos(config.headAwayDurationSeconds());
        this.persistentFaceLossNanos = nanos(config.persistentFaceLossSeconds());
        this.eyeVisibilityLostNanos = nanos(config.eyeVisibilityLostSeconds());
    }

    @Override
    public DriverRiskSnapshot evaluate(DriverState state) {
        Objects.requireNonNull(state, "state");
        List<DriverRiskReason> reasons = new ArrayList<>();
        RiskLevel level = RiskLevel.NORMAL;

        // ---- eye-closure evidence (source-time duration, never a single frame) ----
        boolean warningLevelClosure = false;
        if (state.eyeState() == EyeState.CLOSED) {
            reasons.add(DriverRiskReason.EYES_CLOSED);
            long closure = state.continuousEyeClosureNanos();
            if (closure >= criticalClosureNanos) {
                level = RiskLevel.CRITICAL;
                reasons.add(DriverRiskReason.PROLONGED_EYE_CLOSURE);
            } else if (closure >= warningClosureNanos) {
                warningLevelClosure = true;
                level = RiskLevel.WARNING;
                reasons.add(DriverRiskReason.PROLONGED_EYE_CLOSURE);
            } else if (closure >= cautionClosureNanos) {
                level = RiskLevel.CAUTION;
                reasons.add(DriverRiskReason.PROLONGED_EYE_CLOSURE);
            }
            // Closure below the caution threshold is a normal blink: EYES_CLOSED stays
            // informative and never escalates.
        }

        // ---- windowed time-weighted PERCLOS-like evidence (coverage-aware; may be unavailable) ----
        boolean warningLevelPerclos = false;
        if (state.perclos().available()) {
            float perclos = state.perclos().value();
            if (perclos >= config.perclosWarningThreshold()) {
                warningLevelPerclos = true;
                level = max(level, RiskLevel.WARNING);
                reasons.add(DriverRiskReason.HIGH_PERCLOS);
            } else if (perclos >= config.perclosCautionThreshold()) {
                level = max(level, RiskLevel.CAUTION);
                reasons.add(DriverRiskReason.HIGH_PERCLOS);
            }
        }

        // ---- sustained closure AND sustained closure-fraction strengthen each other ----
        if (warningLevelClosure && warningLevelPerclos) {
            // Escalation is explicitly explained: both codes (PROLONGED_EYE_CLOSURE, HIGH_PERCLOS)
            // are already present in the reasons list.
            level = RiskLevel.CRITICAL;
        }

        // ---- yawn-like evidence (persistence already enforced by the analyzer) ----
        if (state.yawnLikeState() == YawnLikeState.YAWN_LIKE) {
            level = max(level, RiskLevel.CAUTION);
            reasons.add(DriverRiskReason.YAWN_LIKE_EVENT);
        }

        // ---- persistent head-direction evidence ----
        if (state.headPoseState() == HeadPoseState.DOWN
                && state.continuousHeadAwayNanos() >= headAwayNanos) {
            level = max(level, RiskLevel.WARNING);
            reasons.add(DriverRiskReason.LOOKING_DOWN);
        } else if ((state.headPoseState() == HeadPoseState.LEFT
                || state.headPoseState() == HeadPoseState.RIGHT)
                && state.continuousHeadAwayNanos() >= headAwayNanos) {
            level = max(level, RiskLevel.CAUTION);
            reasons.add(DriverRiskReason.HEAD_AWAY);
        }

        // ---- monitoring / visibility evidence (distinct from fatigue evidence) ----
        if (!state.faceDetected()) {
            reasons.add(DriverRiskReason.FACE_NOT_DETECTED);
            if (state.continuousFaceLossNanos() >= persistentFaceLossNanos) {
                level = max(level, RiskLevel.CAUTION);
                reasons.add(DriverRiskReason.DRIVER_VISIBILITY_LOST);
            }
        } else if (state.continuousEyeUnavailableNanos() >= eyeVisibilityLostNanos) {
            level = max(level, RiskLevel.CAUTION);
            reasons.add(DriverRiskReason.INSUFFICIENT_EYE_VISIBILITY);
        }

        // ---- informative observation quality (never escalates by itself) ----
        if (state.observationQuality() != ObservationQuality.GOOD
                || state.timestampRejection() != DriverState.TimestampRejection.NONE) {
            reasons.add(DriverRiskReason.LOW_OBSERVATION_QUALITY);
        }

        return new DriverRiskSnapshot(state.timestampNanos(), DriverRiskSnapshot.Status.READY,
                Optional.of(level), List.copyOf(reasons), state);
    }

    private static RiskLevel max(RiskLevel a, RiskLevel b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static long nanos(double seconds) {
        return Math.round(seconds * 1_000_000_000d);
    }
}
