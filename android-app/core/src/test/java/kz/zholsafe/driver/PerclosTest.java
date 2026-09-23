package kz.zholsafe.driver;

import kz.zholsafe.config.DriverGuardConfig;
import org.junit.jupiter.api.Test;

import static kz.zholsafe.driver.DriverGuardTestSupport.config;
import static kz.zholsafe.driver.DriverGuardTestSupport.faceEyes;
import static kz.zholsafe.driver.DriverGuardTestSupport.faceNoEyes;
import static kz.zholsafe.driver.DriverGuardTestSupport.noFace;
import static kz.zholsafe.driver.DriverGuardTestSupport.tm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Required Stage 4.3 PERCLOS tests: time-weighting (not frame counting), irregular intervals,
 * missing-face / unknown-eye exclusion, coverage-aware availability, window eviction and the
 * hard observation cap (bounded memory).
 */
class PerclosTest {

    private static final float OPEN = 0.95f;
    private static final float CLOSED = 0.0f;

    private static DriverState feedSeconds(TemporalDriverStateAnalyzer analyzer, double stepSeconds,
                                           double toSeconds, char[] plan) {
        // plan.get(c) tells whether eyes are CLOSED ('C'), OPEN ('O') or UNKNOWN ('U') at that instant
        DriverState state = analyzer.current();
        long step = Math.round(stepSeconds * 1000d);
        for (long ms = 0; ms <= Math.round(toSeconds * 1000d); ms += step) {
            int block = (int) (ms / step);
            char c = plan[Math.min(block, plan.length - 1)];
            long ts = tm(ms);
            if (c == 'U') {
                state = analyzer.update(faceNoEyes(ts));
            } else if (c == 'X') {
                state = analyzer.update(noFace(ts));
            } else {
                state = analyzer.update(faceEyes(ts, c == 'C' ? CLOSED : OPEN));
            }
        }
        return state;
    }

    @Test
    void mostlyOpenGivesLowPerclos() {
        // 0.5 s cadence over 100 s; eyes "closed" for two samples (~1 s of elapsed time).
        TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(DriverGuardConfig.defaults());
        char[] plan = new char[201];
        java.util.Arrays.fill(plan, 'O');
        plan[100] = 'C';
        plan[101] = 'C';
        DriverState state = feedSeconds(analyzer, 0.5, 100, plan);

        assertTrue(state.perclos().available(), "60 s valid >> 30 s required coverage");
        // Last 60 s window is fully valid except ~1 s closed: 1/60 ≈ 0.017.
        assertEquals(1.0f / 60.0f, state.perclos().value(), 0.01f,
                "mostly open must produce a low PERCLOS");
    }

    @Test
    void halfClosedElapsedTimeWithIrregularIntervalsIsApproximatelyHalf() {
        // Irregular sampling (gaps up to ~6.6 s; gap budget 20 s keeps continuity).
        DriverGuardConfig cfg = config(60d, 20d, 0.5f, 4096);
        TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(cfg);
        double[] closedAt = { 0.0, 5.5, 12.2, 18.1, 24.7 };
        double[] openAt = { 30.3, 35.6, 42.4, 48.9, 54.5, 60.0 };
        DriverState state = analyzer.current();
        for (double s : closedAt) {
            state = analyzer.update(faceEyes(DriverGuardTestSupport.t(s), CLOSED));
        }
        for (double s : openAt) {
            state = analyzer.update(faceEyes(DriverGuardTestSupport.t(s), OPEN));
        }
        // Closed segments [0→30.3] = 30.3 s; valid observed time = 60 s. 6 closed samples vs 6 open
        // samples would wrongly give 0.5 by frame counting whatever the timing; time weighting
        // gives 30.3/60 — assert the SOURCE-TIME value, not the count-based one.
        assertTrue(state.perclos().available());
        assertEquals(30.3f / 60.0f, state.perclos().value(), 0.02f);
    }

    @Test
    void missingFaceIsNotCountedAsOpen() {
        TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(DriverGuardConfig.defaults());
        char[] plan = new char[61];
        java.util.Arrays.fill(plan, 0, 31, 'C');   // closed, visible, 1 s cadence for 31 samples
        java.util.Arrays.fill(plan, 31, 61, 'X');  // face missing afterwards
        DriverState state = feedSeconds(analyzer, 1.0, 60, plan);

        assertTrue(state.perclos().available());
        // Only the visible 31 s enters the denominator; all of it was closed. Counting the missing
        // 29 s as OPEN would give ≈0.5 — it must not.
        assertEquals(1.0f, state.perclos().value(), 1e-6f, "missing != open, missing != closed");
    }

    @Test
    void insufficientValidCoverageMakesPerclosUnavailableNotZero() {
        TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(DriverGuardConfig.defaults());
        char[] burst = { 'O', 'O' };
        DriverState state = feedSeconds(analyzer, 0.5, 0.5, burst);
        // Then a single observation after a >max-gap silence: no fresh valid time accumulates.
        state = analyzer.update(faceEyes(tm(10_000), OPEN));

        assertFalse(state.perclos().available(), "valid coverage far below the 50%×window budget");
        assertTrue(Float.isNaN(state.perclos().value()), "unavailable PERCLOS is NaN, never 0");
    }

    @Test
    void windowEvictionDropsOldObservations() {
        TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(DriverGuardConfig.defaults());
        char[] plan = new char[181];
        java.util.Arrays.fill(plan, 'O');
        java.util.Arrays.fill(plan, 0, 41, 'C'); // closed during [0 s, 20.5 s], 0.5 s cadence
        DriverState state = feedSeconds(analyzer, 0.5, 90, plan);

        assertTrue(state.perclos().available());
        // The [30 s, 90 s] window contains only open time; the old closed period was evicted.
        // Without eviction the metric would report ≈ 20.5/90 > 0.2.
        assertEquals(0.0f, state.perclos().value(), 1e-6f);
    }

    @Test
    void unknownEyeStateIsExcludedFromTheValidDenominator() {
        TemporalDriverStateAnalyzer analyzer = new TemporalDriverStateAnalyzer(DriverGuardConfig.defaults());
        char[] plan = new char[61];
        java.util.Arrays.fill(plan, 0, 31, 'C');   // closed with valid eye data
        java.util.Arrays.fill(plan, 31, 61, 'U');  // face visible, eyes UNKNOWN
        DriverState state = feedSeconds(analyzer, 1.0, 60, plan);

        assertEquals(EyeState.UNKNOWN, state.eyeState());
        assertTrue(state.perclos().available());
        assertEquals(1.0f, state.perclos().value(), 1e-6f,
                "UNKNOWN eye time must enter neither numerator nor denominator");
    }

    @Test
    void hardObservationCapBoundsRetainedHistory() {
        // Window 3 s, coverage 0.4 (≥1.2 s valid needed), hard cap 16 samples, 100 ms cadence.
        TemporalDriverStateAnalyzer capped =
                new TemporalDriverStateAnalyzer(config(3d, 1d, 0.4f, 16));
        for (int k = 0; k <= 100; k++) {
            capped.update(faceEyes(tm(k * 100L), k >= 85 ? CLOSED : OPEN));
        }
        DriverState cappedState = capped.current();
        // Cap keeps only the last 16 samples (1.5 s, all closed).
        assertTrue(cappedState.perclos().available());
        assertEquals(1.0f, cappedState.perclos().value(), 0.01f);

        // Control without the cap: the same 3 s window retains ~3 s of mixed data.
        TemporalDriverStateAnalyzer uncapped =
                new TemporalDriverStateAnalyzer(config(3d, 1d, 0.4f, 4096));
        for (int k = 0; k <= 100; k++) {
            uncapped.update(faceEyes(tm(k * 100L), k >= 85 ? CLOSED : OPEN));
        }
        DriverState uncappedState = uncapped.current();
        // The uncapped window keeps all ~3 s (mixed history): ≈0.5, and it differs from the
        // capped result — proving the hard cap actually bounds retained memory.
        assertTrue(uncappedState.perclos().available());
        assertEquals(0.5f, uncappedState.perclos().value(), 0.1f,
                "uncapped window retains the mixed open/closed history");
        assertTrue(cappedState.perclos().value() > 0.9f,
                "the hard cap must change the retained history — memory is bounded");
    }
}
