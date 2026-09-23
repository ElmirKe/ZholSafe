package kz.zholsafe.ai;

import kz.zholsafe.model.ObjectClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabelMapTest {

    @Test
    void mapsKnownLabelsAndReportsUnknownOnes() {
        LabelMap map = new LabelMap(List.of("person", "dog", "horse", "cow", "sheep", "tractor"));
        assertEquals(ObjectClass.PERSON, map.classFor(0));
        assertEquals(ObjectClass.SHEEP, map.classFor(4));
        assertEquals(ObjectClass.UNKNOWN, map.classFor(5));
        assertEquals(ObjectClass.UNKNOWN, map.classFor(99));
        assertEquals(List.of("tractor"), map.unmappedLabels());
        assertEquals("tractor", map.labelFor(5));
    }
}
