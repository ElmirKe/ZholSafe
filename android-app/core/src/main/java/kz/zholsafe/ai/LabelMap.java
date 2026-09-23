package kz.zholsafe.ai;

import kz.zholsafe.model.ObjectClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Maps model output class indices to {@link ObjectClass}.
 *
 * <p>Built from the label file shipped next to the model (one label per line). Labels not
 * recognised by {@link ObjectClass#fromLabel(String)} map to {@link ObjectClass#UNKNOWN} and are
 * reported via {@link #unmappedLabels()} so the diagnostic is visible at model-load time rather
 * than silently swallowed.
 */
public final class LabelMap {

    private final List<String> labels;
    private final List<ObjectClass> classes;
    private final List<String> unmapped;

    public LabelMap(List<String> labels) {
        Objects.requireNonNull(labels, "labels");
        this.labels = List.copyOf(labels);
        List<ObjectClass> mapped = new ArrayList<>(labels.size());
        List<String> unmappedTmp = new ArrayList<>();
        for (String label : labels) {
            ObjectClass c = ObjectClass.fromLabel(label).orElse(ObjectClass.UNKNOWN);
            if (c == ObjectClass.UNKNOWN) {
                unmappedTmp.add(label);
            }
            mapped.add(c);
        }
        this.classes = Collections.unmodifiableList(mapped);
        this.unmapped = Collections.unmodifiableList(unmappedTmp);
    }

    public int size() {
        return labels.size();
    }

    public ObjectClass classFor(int index) {
        if (index < 0 || index >= classes.size()) {
            return ObjectClass.UNKNOWN;
        }
        return classes.get(index);
    }

    public String labelFor(int index) {
        if (index < 0 || index >= labels.size()) {
            return "unknown";
        }
        return labels.get(index);
    }

    /** Labels present in the model but unknown to {@link ObjectClass}. Should be logged as WARN. */
    public List<String> unmappedLabels() {
        return unmapped;
    }
}
