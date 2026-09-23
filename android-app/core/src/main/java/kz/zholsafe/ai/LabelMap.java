package kz.zholsafe.ai;

import kz.zholsafe.model.ObjectClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Maps a model's output class index → model label → canonical {@link ObjectClass}.
 *
 * <p><b>Hard rule:</b> a model class index is NEVER interpreted as an {@code ObjectClass}
 * ordinal. The only path is through the label string. Labels that do not correspond to a
 * canonical class map to {@link ObjectClass#UNKNOWN} and are reported by
 * {@link #isSupported(int)} as unsupported; the detector <em>ignores</em> such detections
 * (policy: unsupported model class → dropped, not re-labelled).
 *
 * <p>Optional aliases let a model-spec declare e.g. {@code "cattle" → "cow"} explicitly. Aliases
 * are label→label strings so they stay reviewable in JSON; they never reference indices.
 */
public final class LabelMap {

    private final List<String> labels;
    private final List<ObjectClass> classes;
    private final List<String> unmapped;

    public LabelMap(List<String> labels) {
        this(labels, Map.of());
    }

    public LabelMap(List<String> labels, Map<String, String> aliases) {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(aliases, "aliases");
        this.labels = List.copyOf(labels);
        List<ObjectClass> mapped = new ArrayList<>(labels.size());
        List<String> unmappedTmp = new ArrayList<>();
        for (String label : labels) {
            String key = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
            String target = aliases.getOrDefault(key, key);
            ObjectClass c = ObjectClass.fromLabel(target).orElse(ObjectClass.UNKNOWN);
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

    /** Canonical class for a model index, or UNKNOWN when out of range / unsupported. */
    public ObjectClass classFor(int index) {
        if (index < 0 || index >= classes.size()) {
            return ObjectClass.UNKNOWN;
        }
        return classes.get(index);
    }

    /** True iff the index is in range and maps to a canonical class other than UNKNOWN. */
    public boolean isSupported(int index) {
        return classFor(index) != ObjectClass.UNKNOWN;
    }

    public String labelFor(int index) {
        if (index < 0 || index >= labels.size()) {
            return "unknown";
        }
        return labels.get(index);
    }

    public List<String> labels() {
        return labels;
    }

    /** Model labels with no canonical counterpart (ignored at inference time). */
    public List<String> unmappedLabels() {
        return unmapped;
    }

    /** Canonical classes this model can actually emit, in ObjectClass order. */
    public List<ObjectClass> supportedClasses() {
        List<ObjectClass> out = new ArrayList<>();
        for (ObjectClass c : ObjectClass.values()) {
            if (c != ObjectClass.UNKNOWN && classes.contains(c)) {
                out.add(c);
            }
        }
        return Collections.unmodifiableList(out);
    }
}
