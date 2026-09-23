package kz.zholsafe.server.hazard;

/** Engineering severity for network prioritisation; never a probability or safety guarantee. */
public enum HazardSeverity {
    NORMAL(0), CAUTION(1), WARNING(2), CRITICAL(3);

    private final short rank;

    HazardSeverity(int rank) {
        this.rank = (short) rank;
    }

    public short rank() {
        return rank;
    }

    /** Backward-compatible mapping for contract-v1 clients that only send {@code risk}. */
    public static HazardSeverity fromRisk(float risk) {
        if (risk >= 0.85f) return CRITICAL;
        if (risk >= 0.65f) return WARNING;
        if (risk >= 0.35f) return CAUTION;
        return NORMAL;
    }
}
