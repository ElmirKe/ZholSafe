package kz.zholsafe.ai;

import kz.zholsafe.model.ObjectClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelMapPolicyTest {

    /** First 20 COCO labels in COCO order (as exported by Ultralytics pretrained models). */
    static final List<String> COCO_HEAD = List.of("person", "bicycle", "car", "motorcycle", "airplane", "bus", "train",
            "truck", "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow");

    @Test
    void cocoLabelsMapByNameNotByIndex() {
        LabelMap m = new LabelMap(COCO_HEAD);
        assertEquals(ObjectClass.PERSON, m.classFor(0));
        assertEquals(ObjectClass.DOG, m.classFor(16));
        assertEquals(ObjectClass.HORSE, m.classFor(17));
        assertEquals(ObjectClass.SHEEP, m.classFor(18));
        assertEquals(ObjectClass.COW, m.classFor(19));
    }

    @Test
    void numericCoincidenceCannotBypassLabelMap() {
        // ObjectClass.DOG.ordinal()==1, HORSE==2, COW==3, SHEEP==4 — in COCO those indices are
        // bicycle/car/motorcycle/airplane and MUST be ignored, not mapped.
        LabelMap m = new LabelMap(COCO_HEAD);
        for (int i = 1; i <= 4; i++) {
            assertEquals(ObjectClass.UNKNOWN, m.classFor(i), "index " + i);
            assertFalse(m.isSupported(i));
        }
        assertEquals(ObjectClass.UNKNOWN, m.classFor(ObjectClass.CAMEL.ordinal()));
    }

    @Test
    void unsupportedLabelsAreIgnoredAndReported() {
        LabelMap m = new LabelMap(List.of("person", "tractor", "dog"));
        assertFalse(m.isSupported(1));
        assertEquals(List.of("tractor"), m.unmappedLabels());
        assertEquals(List.of(ObjectClass.PERSON, ObjectClass.DOG), m.supportedClasses());
    }

    @Test
    void goatAndCamelOnlyWhenPresentInModelLabels() {
        LabelMap coco = new LabelMap(COCO_HEAD);
        assertFalse(coco.supportedClasses().contains(ObjectClass.GOAT));
        assertFalse(coco.supportedClasses().contains(ObjectClass.CAMEL));
        LabelMap custom = new LabelMap(List.of("person", "dog", "horse", "cow", "sheep", "goat", "camel"));
        assertTrue(custom.supportedClasses().contains(ObjectClass.GOAT));
        assertTrue(custom.supportedClasses().contains(ObjectClass.CAMEL));
        assertEquals(ObjectClass.CAMEL, custom.classFor(6));
    }

    @Test
    void aliasesAreExplicitLabelStringsOnly() {
        LabelMap m = new LabelMap(List.of("cattle", "hound"), Map.of("cattle", "cow"));
        assertEquals(ObjectClass.COW, m.classFor(0));
        assertEquals(ObjectClass.UNKNOWN, m.classFor(1));
    }

    @Test
    void outOfRangeIsUnknownAndUnsupported() {
        LabelMap m = new LabelMap(List.of("person"));
        assertEquals(ObjectClass.UNKNOWN, m.classFor(-1));
        assertEquals(ObjectClass.UNKNOWN, m.classFor(1));
        assertFalse(m.isSupported(99));
        assertTrue(m.isSupported(0));
    }
}
