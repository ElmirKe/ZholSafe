package kz.zholsafe.physical;

import kz.zholsafe.model.Contracts;
import kz.zholsafe.model.ObjectClass;

import java.util.Objects;

/** Explicitly sourced object HEIGHT prior in metres, never a universal exact class height. */
public record ObjectSizePrior(ObjectClass objectClass, SizeDimension dimension,
        double minimumMeters, double nominalMeters, double maximumMeters, PriorSource source) {
    public ObjectSizePrior {
        Objects.requireNonNull(objectClass, "objectClass");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(source, "source");
        if (objectClass == ObjectClass.UNKNOWN) throw new IllegalArgumentException("UNKNOWN has no size prior");
        positive("minimumMeters", minimumMeters);
        positive("nominalMeters", nominalMeters);
        positive("maximumMeters", maximumMeters);
        if (minimumMeters > nominalMeters || nominalMeters > maximumMeters) {
            throw new IllegalArgumentException("minimum <= nominal <= maximum required");
        }
    }

    private static void positive(String name, double value) {
        Contracts.finite(name, value);
        if (value <= 0d) throw new IllegalArgumentException(name + " must be > 0");
    }
}
