package kz.zholsafe.network;

import kz.zholsafe.model.ObjectClass;

import java.util.Optional;

/** Maps only classes supported by the currently verified YOLO11n COCO detector. */
public final class HazardClassMapper {
    private HazardClassMapper() {}
    public static Optional<NetworkHazardType> fromVerifiedDetector(ObjectClass value) {
        if (value == null || !value.isInitialTarget()) return Optional.empty();
        return switch (value) {
            case PERSON -> Optional.of(NetworkHazardType.PERSON);
            case DOG -> Optional.of(NetworkHazardType.DOG);
            case HORSE -> Optional.of(NetworkHazardType.HORSE);
            case COW -> Optional.of(NetworkHazardType.COW);
            case SHEEP -> Optional.of(NetworkHazardType.SHEEP);
            case GOAT, CAMEL, UNKNOWN -> Optional.empty();
        };
    }
}
