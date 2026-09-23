package kz.zholsafe.driver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariants of the Stage 4.3 {@link DriverState} / {@link PerclosValue} data contracts
 * (see docs/DATA_CONTRACTS.md). Updated from the Stage 0 placeholder contract to the temporal
 * source-time contract.
 */
class DriverStateInvariantsTest {

    private static final long TS = DriverGuardTestSupport.T0;

    private static DriverState state(boolean face, EyeState eye, long closureNanos, PerclosValue perclos,
                                     float confidence) {
        return new DriverState(TS, face, eye, closureNanos, perclos,
                HeadPoseState.UNKNOWN, 0L, HeadPose.UNAVAILABLE,
                face ? YawnLikeState.NONE : YawnLikeState.UNAVAILABLE, 0L, 0L, 0L,
                ObservationQuality.GOOD, confidence, DriverState.TimestampRejection.NONE);
    }

    @Test
    void durationsMustBeNonNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> state(true, EyeState.OPEN, -1L, PerclosValue.unavailable(0L, 0L), 0.9f));
        assertDoesNotThrow(() -> state(true, EyeState.OPEN, 0L, PerclosValue.unavailable(0L, 0L), 0.9f));
        assertThrows(IllegalArgumentException.class, () -> new DriverState(TS, true, EyeState.UNKNOWN, 0L,
                PerclosValue.unavailable(0L, 0L), HeadPoseState.UNKNOWN, -1L, HeadPose.UNAVAILABLE,
                YawnLikeState.UNAVAILABLE, 0L, 0L, 0L, ObservationQuality.DEGRADED, 0.9f,
                DriverState.TimestampRejection.NONE));
    }

    @Test
    void positiveClosureDurationRequiresClosedEyes() {
        assertThrows(IllegalArgumentException.class,
                () -> state(true, EyeState.OPEN, 5L, PerclosValue.unavailable(0L, 0L), 0.9f));
        assertDoesNotThrow(() -> state(true, EyeState.OPEN, 0L, PerclosValue.unavailable(0L, 0L), 0.9f));
        assertDoesNotThrow(() -> state(true, EyeState.CLOSED, 5L, PerclosValue.unavailable(0L, 0L), 0.9f));
    }

    @Test
    void missingFaceCannotCarryFaceDerivedState() {
        // UNKNOWN eyes are mandatory without a face.
        assertThrows(IllegalArgumentException.class,
                () -> state(false, EyeState.CLOSED, 0L, PerclosValue.unavailable(0L, 0L), 0.9f));
        assertThrows(IllegalArgumentException.class,
                () -> state(false, EyeState.OPEN, 0L, PerclosValue.unavailable(0L, 0L), 0.9f));
        assertDoesNotThrow(() -> state(false, EyeState.UNKNOWN, 0L, PerclosValue.unavailable(0L, 0L), 0f));
        // Head/yawn/pose cannot exist without a face either.
        assertThrows(IllegalArgumentException.class, () -> new DriverState(TS, false, EyeState.UNKNOWN, 0L,
                PerclosValue.unavailable(0L, 0L), HeadPoseState.FORWARD, 0L, HeadPose.UNAVAILABLE,
                YawnLikeState.UNAVAILABLE, 0L, 0L, 0L, ObservationQuality.UNAVAILABLE, 0f,
                DriverState.TimestampRejection.NONE));
        assertThrows(IllegalArgumentException.class, () -> new DriverState(TS, false, EyeState.UNKNOWN, 0L,
                PerclosValue.unavailable(0L, 0L), HeadPoseState.UNKNOWN, 0L, HeadPose.UNAVAILABLE,
                YawnLikeState.MOUTH_OPEN, 0L, 0L, 0L, ObservationQuality.UNAVAILABLE, 0f,
                DriverState.TimestampRejection.NONE));
        assertThrows(IllegalArgumentException.class, () -> new DriverState(TS, false, EyeState.UNKNOWN, 0L,
                PerclosValue.unavailable(0L, 0L), HeadPoseState.UNKNOWN, 0L, HeadPose.of(1f, 2f, 3f),
                YawnLikeState.UNAVAILABLE, 0L, 0L, 0L, ObservationQuality.UNAVAILABLE, 0f,
                DriverState.TimestampRejection.NONE));
    }

    @Test
    void confidenceMustBeFiniteUnit() {
        assertThrows(IllegalArgumentException.class,
                () -> state(true, EyeState.OPEN, 0L, PerclosValue.unavailable(0L, 0L), Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> state(true, EyeState.OPEN, 0L, PerclosValue.unavailable(0L, 0L), 1.2f));
    }

    @Test
    void perclosContract() {
        // Available ⇒ value finite in [0,1], valid ≤ window, positive spans.
        assertDoesNotThrow(() -> new PerclosValue(true, 0.3f, 30_000_000_000L, 60_000_000_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new PerclosValue(true, Float.NaN, 30L, 60L));
        assertThrows(IllegalArgumentException.class,
                () -> new PerclosValue(true, 1.5f, 30L, 60L));
        assertThrows(IllegalArgumentException.class,
                () -> new PerclosValue(true, 0.5f, 0L, 60L));
        assertThrows(IllegalArgumentException.class,
                () -> new PerclosValue(false, 0.3f, 30L, 60L),
                "numeric value while unavailable would be a fabricated measurement");
        assertThrows(IllegalArgumentException.class,
                () -> new PerclosValue(false, Float.NaN, 70L, 60L),
                "valid time cannot exceed the window");
    }

    @Test
    void unavailableFactoriesStayValid() {
        assertDoesNotThrow(() -> DriverState.unavailable(TS));
        assertDoesNotThrow(() -> DriverObservation.noFace(TS));
        DriverState unavailable = DriverState.unavailable(TS);
        assertFalse(unavailable.faceDetected());
        assertFalse(unavailable.perclosAvailable());
        assertFalse(unavailable.eyesClosed());
        assertFalse(unavailable.yawningDetected());
        assertEquals(0L, unavailable.eyeClosureDurationMillis());
    }

    @Test
    void legacyAccessorsReflectTheTemporalState() {
        DriverState closed = state(true, EyeState.CLOSED, 2_000_000_000L,
                new PerclosValue(true, 0.4f, 30_000_000_000L, 60_000_000_000L), 0.9f);
        assertTrue(closed.eyesClosed());
        assertEquals(2_000L, closed.eyeClosureDurationMillis());
        assertTrue(closed.perclosAvailable());
        assertEquals(0.4f, closed.perclos().value(), 1e-6f);

        DriverState yawn = new DriverState(TS, true, EyeState.OPEN, 0L, PerclosValue.unavailable(0L, 0L),
                HeadPoseState.FORWARD, 0L, HeadPose.UNAVAILABLE, YawnLikeState.YAWN_LIKE,
                3_000_000_000L, 0L, 0L, ObservationQuality.GOOD, 0.9f,
                DriverState.TimestampRejection.NONE);
        assertTrue(yawn.yawningDetected());
    }

    @Test
    void withRejectionPreservesAcceptedData() {
        DriverState accepted = state(true, EyeState.OPEN, 0L, PerclosValue.unavailable(0L, 0L), 0.9f);
        DriverState rejected = DriverState.withRejection(accepted,
                DriverState.TimestampRejection.REVERSED_TIMESTAMP);
        assertEquals(DriverState.TimestampRejection.REVERSED_TIMESTAMP, rejected.timestampRejection());
        assertEquals(accepted.timestampNanos(), rejected.timestampNanos());
        assertEquals(accepted.eyeState(), rejected.eyeState());
    }
}
