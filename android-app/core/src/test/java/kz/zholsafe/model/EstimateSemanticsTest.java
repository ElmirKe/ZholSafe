package kz.zholsafe.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 0.1: distance and TTC sign semantics (see Estimate javadoc). */
class EstimateSemanticsTest {

    @Test
    void distanceMustBeNonNegative() {
        assertThrows(IllegalArgumentException.class, () -> Estimate.distance(-0.5, EstimationMethod.MONOCULAR_UNCALIBRATED));
        assertEquals(0d, Estimate.distance(0d, EstimationMethod.MONOCULAR_UNCALIBRATED).value());
        assertTrue(Estimate.distance(12d, EstimationMethod.STEREO).isNonNegative());
    }

    @Test
    void negativeTtcIsNotAValidEstimate() {
        // Contract: negative TTC ("closest approach already passed" / not closing) => unavailable.
        assertThrows(IllegalArgumentException.class, () -> Estimate.ttc(-1.0, EstimationMethod.SCALE_CHANGE));
        assertTrue(Estimate.ttc(0d, EstimationMethod.SCALE_CHANGE).available());
        assertFalse(Estimate.unavailable().isNonNegative());
    }

    @Test
    void genericOfStillAllowsSignedQuantitiesButFlagsThemViaIsNonNegative() {
        Estimate signed = Estimate.of(-2d, EstimationMethod.VEHICLE_SENSOR);
        assertTrue(signed.available());
        assertFalse(signed.isNonNegative());
    }
}
