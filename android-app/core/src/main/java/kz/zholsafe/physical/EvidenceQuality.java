package kz.zholsafe.physical;

/** Engineering evidence grade, NOT a statistical probability or safety certification. */
public enum EvidenceQuality {
    UNAVAILABLE, LOW, MEDIUM, HIGH;

    public boolean atLeast(EvidenceQuality required) {
        return this != UNAVAILABLE && ordinal() >= required.ordinal();
    }
}
