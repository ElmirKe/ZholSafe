package kz.zholsafe.config;

import kz.zholsafe.model.ObjectClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDefaultsTest {

    @Test
    void defaultsAreWellFormed() {
        ZholSafeConfig c = ZholSafeConfig.defaults(ZholSafeConfig.OperatingMode.DEMO);
        assertEquals(ZholSafeConfig.OperatingMode.DEMO, c.mode());
        assertNotNull(c.detector().roadModel().path());
        assertTrue(c.detector().inferenceQueueCapacity() > 0);
        assertTrue(c.tracking().corridorLeftFraction() < c.tracking().corridorRightFraction());
    }

    @Test
    void everyObjectClassHasARiskWeight() {
        RiskConfig r = RiskConfig.defaults();
        for (ObjectClass c : ObjectClass.values()) {
            float w = r.classWeight(c);
            assertTrue(w >= 0f && w <= 1f, c + " weight " + w);
        }
    }
}
