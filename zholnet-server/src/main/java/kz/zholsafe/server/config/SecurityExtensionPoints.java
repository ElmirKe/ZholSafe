package kz.zholsafe.server.config;

/**
 * Documented extension points for future security work (NOT implemented in Stage 0).
 *
 * <ul>
 *   <li><b>Device identity</b>: replace self-declared {@code vehicleId} with a server-issued
 *       identity bound to a credential (e.g. per-device token / mTLS).</li>
 *   <li><b>Authentication</b>: Spring Security filter on {@code /api/**} and the WebSocket
 *       handshake.</li>
 *   <li><b>Authorization</b>: vehicles may only submit events for their own identity; map/analytics
 *       endpoints get an operator role.</li>
 *   <li><b>Event validation</b>: {@link kz.zholsafe.server.hazard.HazardEventValidator} plus
 *       plausibility and rate limiting per vehicle.</li>
 *   <li><b>Secrets</b>: environment variables / external config only; never in the repo.</li>
 * </ul>
 * Incoming events from other vehicles must always be treated as untrusted on the client too.
 */
public final class SecurityExtensionPoints {
    private SecurityExtensionPoints() { }
}
