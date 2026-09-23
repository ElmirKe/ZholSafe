package kz.zholsafe.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrajectoryConfigTest {
    private static TrajectoryConfig of(int min, int max, double span, double stationary, double diagonal,
                                       double stable, double growth, double shrink, double centerRms, double areaRms) {
        return new TrajectoryConfig(min, max, span, stationary, diagonal, stable, growth, shrink, centerRms, areaRms);
    }

    @Test void defaultsAreEngineeringOnlyAndWindowFitsStage3History() {
        TrajectoryConfig c = TrajectoryConfig.defaults();
        assertTrue(c.minSamples() >= 3);
        assertTrue(c.maxSamples() <= TrackingConfig.defaults().historyLength());
    }

    @Test void rejectsInvalidWindowsThresholdsAndNonfiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> of(2, 8, .25, .01, .35, .03, .12, .12, .03, .12));
        assertThrows(IllegalArgumentException.class, () -> of(3, 2, .25, .01, .35, .03, .12, .12, .03, .12));
        assertThrows(IllegalArgumentException.class, () -> of(3, 8, 0d, .01, .35, .03, .12, .12, .03, .12));
        assertThrows(IllegalArgumentException.class, () -> of(3, 8, Double.NaN, .01, .35, .03, .12, .12, .03, .12));
        assertThrows(IllegalArgumentException.class, () -> of(3, 8, .25, -.01, .35, .03, .12, .12, .03, .12));
        assertThrows(IllegalArgumentException.class, () -> of(3, 8, .25, .01, 0d, .03, .12, .12, .03, .12));
        assertThrows(IllegalArgumentException.class, () -> of(3, 8, .25, .01, 1.2, .03, .12, .12, .03, .12));
        assertThrows(IllegalArgumentException.class, () -> of(3, 8, .25, .01, .35, .12, .12, .15, .03, .12));
        assertThrows(IllegalArgumentException.class, () -> of(3, 8, .25, .01, .35, .03, .12, .02, .03, .12));
        assertThrows(IllegalArgumentException.class, () -> of(3, 8, .25, .01, .35, .03, .12, .12, 0d, .12));
        assertThrows(IllegalArgumentException.class, () -> of(3, 8, .25, .01, .35, .03, .12, .12, .03, Double.POSITIVE_INFINITY));
    }
}
