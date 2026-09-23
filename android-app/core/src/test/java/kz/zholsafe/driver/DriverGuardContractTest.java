package kz.zholsafe.driver;

import kz.zholsafe.config.DriverGuardConfig;
import kz.zholsafe.pipeline.DriverGuardProcessor;
import kz.zholsafe.risk.CombinedRiskReason;
import kz.zholsafe.risk.CombinedRiskSnapshot;
import kz.zholsafe.risk.DriverRiskEngine;
import kz.zholsafe.risk.DriverRiskReason;
import kz.zholsafe.risk.DriverRiskSnapshot;
import kz.zholsafe.risk.RiskLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

import static kz.zholsafe.driver.DriverGuardTestSupport.faceEyes;
import static kz.zholsafe.driver.DriverGuardTestSupport.faceFull;
import static kz.zholsafe.driver.DriverGuardTestSupport.frameAt;
import static kz.zholsafe.driver.DriverGuardTestSupport.t;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Required Stage 4.3 contract tests: immutability, explicit unavailability (never 0-as-missing,
 * never NaN-as-available), bounded memory, no frame/image retention, determinism.
 */
class DriverGuardContractTest {

    @Test
    void observationRejectsInconsistentData() {
        long ts = t(0.0);
        // NaN while flagged available — a fabricated measurement.
        assertThrows(IllegalArgumentException.class, () -> new DriverObservation(
                ts, true, true, Float.NaN, 0.5f, false, Float.NaN, HeadPose.UNAVAILABLE, 0.9f));
        assertThrows(IllegalArgumentException.class, () -> new DriverObservation(
                ts, true, true, 0.9f, Float.NaN, false, Float.NaN, HeadPose.UNAVAILABLE, 0.9f));
        // Numeric values while flagged unavailable — zero/NaN ambiguity in reverse.
        assertThrows(IllegalArgumentException.class, () -> new DriverObservation(
                ts, true, false, 0.0f, Float.NaN, false, Float.NaN, HeadPose.UNAVAILABLE, 0.9f));
        assertThrows(IllegalArgumentException.class, () -> new DriverObservation(
                ts, true, false, Float.NaN, Float.NaN, false, 0.5f, HeadPose.UNAVAILABLE, 0.9f));
        // Face-derived measurements without a face are contradictory.
        assertThrows(IllegalArgumentException.class, () -> new DriverObservation(
                ts, false, true, 0.5f, 0.5f, false, Float.NaN, HeadPose.UNAVAILABLE, 0.9f));
        // Out-of-range values.
        assertThrows(IllegalArgumentException.class, () -> new DriverObservation(
                ts, true, true, 1.2f, 0.9f, false, Float.NaN, HeadPose.UNAVAILABLE, 0.9f));
        assertThrows(IllegalArgumentException.class, () -> new DriverObservation(
                ts, true, true, 0.9f, 0.9f, false, Float.NaN, HeadPose.UNAVAILABLE, Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new DriverObservation(
                -1L, true, true, 0.9f, 0.9f, false, Float.NaN, HeadPose.UNAVAILABLE, 0.9f));
        // Valid edge: 0.0 openness is a real measurement ("fully closed"), not "unavailable".
        assertDoesNotThrow(() -> faceEyes(ts, 0.0f));
        assertDoesNotThrow(() -> DriverObservation.noFace(ts));
        assertDoesNotThrow(() -> faceEyes(ts, 0.5f).withTimestamp(t(1.0)));
    }

    @Test
    void zeroOpennessMeansMeasuredClosedNotUnavailable() {
        TemporalDriverStateAnalyzer analyzer =
                new TemporalDriverStateAnalyzer(DriverGuardConfig.defaults());
        DriverState state = analyzer.update(faceEyes(t(0.0), 0.0f));
        assertEquals(EyeState.CLOSED, state.eyeState(),
                "0.0 openness is a real CLOSED measurement, never UNKNOWN");
    }

    @Test
    void unavailabilityIsExplicitlyNotZero() {
        DriverObservation noFace = DriverObservation.noFace(t(0.0));
        assertFalse(noFace.eyeOpennessAvailable());
        assertTrue(Float.isNaN(noFace.leftEyeOpenness()), "unavailable is NaN, never 0.0");
        assertTrue(Float.isNaN(noFace.mouthOpenScore()));
        assertFalse(noFace.headPose().available());

        DriverState unavailable = DriverState.unavailable(t(0.0));
        assertFalse(unavailable.perclos().available());
        assertTrue(Float.isNaN(unavailable.perclos().value()));
        assertEquals(EyeState.UNKNOWN, unavailable.eyeState());
        assertEquals(HeadPoseState.UNKNOWN, unavailable.headPoseState());
        assertEquals(YawnLikeState.UNAVAILABLE, unavailable.yawnLikeState());
    }

    @Test
    void snapshotsAreDeeplyImmutable() {
        DriverGuardConfig config = DriverGuardConfig.defaults();
        DriverRiskEngine engine = new DriverRiskEngine(config);
        TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(config);
        DriverRiskSnapshot risk = engine.evaluate(analyzer.update(faceEyes(t(0.0), 0.9f)));
        assertThrows(UnsupportedOperationException.class,
                () -> risk.reasons().add(DriverRiskReason.EYES_CLOSED));

        List<DriverRiskReason> mutable = new ArrayList<>();
        mutable.add(DriverRiskReason.EYES_CLOSED);
        DriverRiskSnapshot custom = new DriverRiskSnapshot(t(0.0), DriverRiskSnapshot.Status.READY,
                java.util.Optional.of(RiskLevel.NORMAL), mutable, analyzer.current());
        mutable.add(DriverRiskReason.HIGH_PERCLOS);
        assertEquals(1, custom.reasons().size(), "defensive copy — later mutation must not leak in");
        assertThrows(UnsupportedOperationException.class,
                () -> custom.reasons().add(DriverRiskReason.YAWN_LIKE_EVENT));

        CombinedRiskSnapshot combined = new kz.zholsafe.risk.CombinedRiskEngine(
                kz.zholsafe.config.CombinedRiskConfig.defaults())
                .evaluate(kz.zholsafe.risk.CombinedRiskTestSupport.road(t(1.0), RiskLevel.NORMAL),
                        kz.zholsafe.risk.CombinedRiskTestSupport.driver(engine, t(1.0), RiskLevel.NORMAL));
        assertThrows(UnsupportedOperationException.class,
                () -> combined.reasons().add(CombinedRiskReason.ROAD_UNAVAILABLE));
    }

    @Test
    void nonNormalRiskWithoutReasonsIsRejectedByContract() {
        DriverGuardConfig config = DriverGuardConfig.defaults();
        TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(config);
        DriverState state = analyzer.update(faceEyes(t(0.0), 0.9f));
        assertThrows(IllegalArgumentException.class, () -> new DriverRiskSnapshot(t(0.0),
                DriverRiskSnapshot.Status.READY, java.util.Optional.of(RiskLevel.WARNING),
                List.of(), state), "non-NORMAL driver risk must be explainable");

        // READY fusion whose combined level exceeds every component without the escalation reason.
        CombinedRiskSnapshot combined = new kz.zholsafe.risk.CombinedRiskEngine(
                kz.zholsafe.config.CombinedRiskConfig.defaults())
                .evaluate(kz.zholsafe.risk.CombinedRiskTestSupport.road(t(1.0), RiskLevel.WARNING),
                        kz.zholsafe.risk.CombinedRiskTestSupport.driver(
                                new DriverRiskEngine(config), t(1.0), RiskLevel.WARNING));
        assertThrows(IllegalArgumentException.class, () -> new CombinedRiskSnapshot(
                combined.evaluationTimestampNanos(), CombinedRiskSnapshot.Status.READY,
                combined.roadLevel(), combined.driverLevel(), java.util.Optional.of(RiskLevel.CRITICAL),
                combined.roadTimestampNanos(), combined.driverTimestampNanos(),
                List.of(CombinedRiskReason.ROAD_HAZARD_PRESENT), combined.roadRisk(),
                combined.driverRisk()), "escalation above component max without the explicit reason");
    }

    @Test
    void contractsRetainNoFramesImagesBuffersOrTensors() {
        for (Class<?> type : List.of(DriverObservation.class, DriverState.class, PerclosValue.class,
                DriverRiskSnapshot.class, CombinedRiskSnapshot.class)) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertFalse(field.getType().isArray(),
                        type.getSimpleName() + "." + field.getName() + " must not hold arrays/buffers");
                String name = field.getType().getName();
                for (String banned : new String[] { "Frame", "Buffer", "Bitmap", "Tensor", "Image" }) {
                    assertFalse(name.contains(banned), () -> type.getSimpleName() + "."
                            + field.getName() + " retains " + name);
                }
            }
        }
    }

    @Test
    void perclosAndClosureBoundsHoldUnderFuzzedSequence() {
        TemporalDriverStateAnalyzer analyzer =
                new TemporalDriverStateAnalyzer(DriverGuardConfig.defaults());
        Random random = new Random(42L);
        long ts = t(0.0);
        DriverState state = analyzer.current();
        for (int i = 0; i < 400; i++) {
            float openness = random.nextFloat();
            float mouth = random.nextFloat();
            HeadPose pose = HeadPose.of(random.nextFloat() * 90f - 45f,
                    random.nextFloat() * 90f - 45f, 0f);
            int kind = random.nextInt(10);
            DriverObservation observation;
            if (kind == 0) {
                observation = DriverObservation.noFace(ts);
            } else if (kind == 1) {
                observation = new DriverObservation(ts, true, false, Float.NaN, Float.NaN,
                        false, Float.NaN, HeadPose.UNAVAILABLE, 0.9f);
            } else {
                observation = faceFull(ts, openness, mouth, pose);
            }
            state = analyzer.update(observation);
            assertTrue(state.continuousEyeClosureNanos() >= 0L);
            assertTrue(state.perclos().available()
                            ? state.perclos().value() >= 0f && state.perclos().value() <= 1f
                            : Float.isNaN(state.perclos().value()),
                    "PERCLOS must stay in [0,1] or be NaN");
            ts += (1 + random.nextInt(300)) * 1_000_000L; // strictly increasing: no rejections
        }
        assertEquals(DriverState.TimestampRejection.NONE, state.timestampRejection());
    }

    @Test
    void chainIsDeterministicAcrossRuns() throws Exception {
        assertEquals(runScriptedChain(), runScriptedChain(),
                "same scripted observations — identical final driver risk");
    }

    private static DriverRiskSnapshot runScriptedChain() throws Exception {
        NavigableMap<Long, DriverObservation> script = new TreeMap<>();
        script.put(t(0.0), faceEyes(t(0.0), 0.9f));
        script.put(t(2.0), faceEyes(t(2.0), 0.0f));
        DriverGuardProcessor processor = new DriverGuardProcessor(
                new SyntheticDriverObservationProvider(script), DriverGuardConfig.defaults());
        for (long ts = t(0.0); ts <= t(4.0); ts += 100_000_000L) {
            processor.process(frameAt(ts));
        }
        return processor.latestRisk();
    }
}
