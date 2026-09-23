package kz.zholsafe.model;

/**
 * Lifecycle status of a {@link HazardEvent} in ZholNet.
 *
 * <ul>
 *   <li>{@code ACTIVE}    – reported and not yet expired.</li>
 *   <li>{@code EXPIRED}   – aged out according to server expiration policy.</li>
 *   <li>{@code CONFIRMED} – corroborated by another vehicle / operator (future multi-vehicle fusion).</li>
 *   <li>{@code DISMISSED} – rejected by validation or an operator.</li>
 * </ul>
 */
public enum HazardStatus {
    ACTIVE,
    EXPIRED,
    CONFIRMED,
    DISMISSED
}
