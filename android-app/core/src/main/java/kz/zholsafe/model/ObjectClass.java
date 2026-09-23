package kz.zholsafe.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Canonical set of road-scene object classes understood by ZholSafe.
 *
 * <p>This enum is the single source of truth for class identity inside the Java code base.
 * Neural-network output indices are NOT hard-coded here: a model ships its own label list and
 * {@link kz.zholsafe.ai.LabelMap} maps model indices to these values. Adding a new class means:
 * <ol>
 *   <li>add a constant here,</li>
 *   <li>add the label to {@code models/road/zholsafe-road-classes.txt},</li>
 *   <li>retrain / re-export the model in {@code ai-training/}.</li>
 * </ol>
 * No other code should need to change (verified by {@code ObjectClassContractTest}).
 */
public enum ObjectClass {
    PERSON("person", HazardCategory.HUMAN, true),
    DOG("dog", HazardCategory.ANIMAL, true),
    HORSE("horse", HazardCategory.ANIMAL, true),
    COW("cow", HazardCategory.ANIMAL, true),
    SHEEP("sheep", HazardCategory.ANIMAL, true),
    GOAT("goat", HazardCategory.ANIMAL, false),
    CAMEL("camel", HazardCategory.ANIMAL, false),
    /** Model produced a class the application does not know; never treated as "no hazard". */
    UNKNOWN("unknown", HazardCategory.UNKNOWN, false);

    /** Broad grouping used by the Risk Engine and analytics. */
    public enum HazardCategory { HUMAN, ANIMAL, VEHICLE, OBSTACLE, UNKNOWN }

    private final String label;
    private final HazardCategory category;
    private final boolean initialTarget;

    ObjectClass(String label, HazardCategory category, boolean initialTarget) {
        this.label = label;
        this.category = category;
        this.initialTarget = initialTarget;
    }

    /** Lower-case label as used in dataset annotations and model label files. */
    public String label() {
        return label;
    }

    public HazardCategory category() {
        return category;
    }

    /** True for the MVP (Stage 2) target classes; false for planned/custom classes. */
    public boolean isInitialTarget() {
        return initialTarget;
    }

    /** Case-insensitive lookup by label or enum name. Returns empty for unknown labels. */
    public static Optional<ObjectClass> fromLabel(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String normalized = label.trim().toLowerCase(Locale.ROOT);
        for (ObjectClass c : values()) {
            if (c.label.equals(normalized) || c.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
