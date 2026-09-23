package kz.zholsafe.physical;

import kz.zholsafe.model.ObjectClass;

import java.util.Map;

/**
 * OPTIONAL engineering guesses for opt-in experiments only; NOT shipped as active defaults.
 * Values are deliberately broad placeholders (metres), not sourced biological measurements,
 * validated distributions or safety-certified dimensions. The app uses Map.of() unless the
 * caller explicitly opts in. A wide prior implies LOW quality and cannot produce metric TTC
 * under default quality gates. Unknown class never has a prior.
 */
public final class ObjectSizePriors {
    private ObjectSizePriors() { }

    public static Map<ObjectClass, ObjectSizePrior> experimentalUnvalidated() {
        return Map.of(
            ObjectClass.PERSON, guess(ObjectClass.PERSON, 1.2, 1.7, 2.2),
            ObjectClass.DOG, guess(ObjectClass.DOG, .2, .6, 1.3),
            ObjectClass.HORSE, guess(ObjectClass.HORSE, 1.0, 1.6, 2.4),
            ObjectClass.COW, guess(ObjectClass.COW, .9, 1.4, 2.1),
            ObjectClass.SHEEP, guess(ObjectClass.SHEEP, .4, .8, 1.4),
            ObjectClass.GOAT, guess(ObjectClass.GOAT, .4, .8, 1.4),
            ObjectClass.CAMEL, guess(ObjectClass.CAMEL, 1.4, 2.1, 3.0));
    }

    private static ObjectSizePrior guess(ObjectClass cls, double min, double nominal, double max) {
        return new ObjectSizePrior(cls, SizeDimension.HEIGHT, min, nominal, max,
                PriorSource.EXPERIMENTAL_UNVALIDATED);
    }
}
