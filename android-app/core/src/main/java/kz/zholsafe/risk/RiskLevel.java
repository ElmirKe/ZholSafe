package kz.zholsafe.risk;

/** Ordered severity levels. Ordinal order is significant: NORMAL &lt; CAUTION &lt; WARNING &lt; CRITICAL. */
public enum RiskLevel {
    NORMAL,
    CAUTION,
    WARNING,
    CRITICAL;

    public boolean isAtLeast(RiskLevel other) {
        return this.ordinal() >= other.ordinal();
    }
}
