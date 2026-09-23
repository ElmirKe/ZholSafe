package kz.zholsafe.driver;

import kz.zholsafe.config.DriverGuardConfig;
import kz.zholsafe.risk.DriverRiskEngine;
import kz.zholsafe.risk.DriverRiskReason;
import kz.zholsafe.risk.DriverRiskSnapshot;
import kz.zholsafe.risk.RiskLevel;
import org.junit.jupiter.api.Test;

import static kz.zholsafe.driver.DriverGuardTestSupport.faceFull;
import static kz.zholsafe.driver.DriverGuardTestSupport.faceNoEyes;
import static kz.zholsafe.driver.DriverGuardTestSupport.t;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Required Stage 4.3 yawn-like and head-pose tests: single frames never escalate,
 * only configured persistence may contribute; unavailable evidence stays UNKNOWN.
 */
class YawnHeadPoseTest {

    private static final float OPEN = 0.95f;
    private static final float MOUTH_SHUT = 0.05f;
    private static final float MOUTH_WIDE = 0.95f;
    private static final HeadPose FORWARD = HeadPose.of(0f, 0f, 0f);

    private final DriverGuardConfig config = DriverGuardConfig.defaults();
    private final TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(config);
    private final DriverRiskEngine risk = new DriverRiskEngine(config);

    @Test
    void singleOpenMouthFrameDoesNotEscalate() {
        DriverState wide = analyzer.update(faceFull(t(0.0), OPEN, MOUTH_WIDE, FORWARD));
        assertEquals(YawnLikeState.MOUTH_OPEN, wide.yawnLikeState(),
                "one open-mouth frame is MOUTH_OPEN, not a yawn-like event");
        DriverRiskSnapshot snapshot = risk.evaluate(wide);
        assertFalse(snapshot.level().orElseThrow().isAtLeast(RiskLevel.CAUTION));
        assertFalse(snapshot.reasons().contains(DriverRiskReason.YAWN_LIKE_EVENT));

        DriverState shut = analyzer.update(faceFull(t(0.3), OPEN, MOUTH_SHUT, FORWARD));
        assertEquals(YawnLikeState.NONE, shut.yawnLikeState());
        assertEquals(0L, shut.continuousYawnLikeNanos());
    }

    @Test
    void sustainedYawnLikeEvidenceEscalatesAfterConfiguredPersistence() {
        // Experimental demo threshold: minimumYawnDurationSeconds = 2.0 s.
        DriverState state = analyzer.current();
        for (long ts = t(0.0); ts <= t(1.9); ts += 250_000_000L) {
            state = analyzer.update(faceFull(ts, OPEN, MOUTH_WIDE, FORWARD));
            assertEquals(YawnLikeState.MOUTH_OPEN, state.yawnLikeState());
            assertFalse(risk.evaluate(state).reasons().contains(DriverRiskReason.YAWN_LIKE_EVENT),
                    "not persistent yet — no event reason");
        }
        state = analyzer.update(faceFull(t(2.0), OPEN, MOUTH_WIDE, FORWARD));
        assertEquals(2_000_000_000L, state.continuousYawnLikeNanos());
        assertEquals(YawnLikeState.YAWN_LIKE, state.yawnLikeState());
        DriverRiskSnapshot snapshot = risk.evaluate(state);
        assertTrue(snapshot.reasons().contains(DriverRiskReason.YAWN_LIKE_EVENT));
        assertEquals(RiskLevel.CAUTION, snapshot.level().orElseThrow(),
                "yawn-like persistence is CAUTION evidence, never instant CRITICAL");
    }

    @Test
    void briefHeadTurnDoesNotWarn() {
        analyzer.update(faceFull(t(0.0), OPEN, MOUTH_SHUT, HeadPose.of(-45f, 0f, 0f))); // LEFT
        DriverState state = analyzer.update(faceFull(t(0.4), OPEN, MOUTH_SHUT, HeadPose.of(-45f, 0f, 0f)));
        assertEquals(HeadPoseState.LEFT, state.headPoseState());
        assertEquals(400_000_000L, state.continuousHeadAwayNanos());

        state = analyzer.update(faceFull(t(0.8), OPEN, MOUTH_SHUT, FORWARD));
        assertEquals(HeadPoseState.FORWARD, state.headPoseState());
        assertEquals(0L, state.continuousHeadAwayNanos());
        DriverRiskSnapshot snapshot = risk.evaluate(state);
        assertEquals(RiskLevel.NORMAL, snapshot.level().orElseThrow(),
                "a brief head turn must not escalate");
        assertFalse(snapshot.reasons().contains(DriverRiskReason.HEAD_AWAY));
    }

    @Test
    void persistentHeadAwayProducesAttentionEvidence() {
        DriverState state = analyzer.current();
        for (long ts = t(0.0); ts <= t(2.1); ts += 300_000_000L) {
            state = analyzer.update(faceFull(ts, OPEN, MOUTH_SHUT, HeadPose.of(40f, 0f, 0f))); // RIGHT
        }
        assertEquals(HeadPoseState.RIGHT, state.headPoseState());
        assertTrue(state.continuousHeadAwayNanos() >= 2_000_000_000L);
        DriverRiskSnapshot snapshot = risk.evaluate(state);
        assertTrue(snapshot.reasons().contains(DriverRiskReason.HEAD_AWAY));
        assertEquals(RiskLevel.CAUTION, snapshot.level().orElseThrow());
    }

    @Test
    void persistentLookingDownIsStronger() {
        DriverState state = analyzer.current();
        for (long ts = t(0.0); ts <= t(2.1); ts += 300_000_000L) {
            state = analyzer.update(faceFull(ts, OPEN, MOUTH_SHUT, HeadPose.of(0f, 40f, 0f))); // DOWN
        }
        assertEquals(HeadPoseState.DOWN, state.headPoseState());
        DriverRiskSnapshot snapshot = risk.evaluate(state);
        assertTrue(snapshot.reasons().contains(DriverRiskReason.LOOKING_DOWN));
        assertEquals(RiskLevel.WARNING, snapshot.level().orElseThrow(),
                "sustained looking-down is WARNING attention evidence");
    }

    @Test
    void unavailableHeadPoseIsUnknownNotForward() {
        DriverState state = analyzer.update(faceNoEyes(t(0.0)));
        assertEquals(HeadPoseState.UNKNOWN, state.headPoseState(),
                "missing pose evidence must be UNKNOWN, never assumed FORWARD");
    }
}
