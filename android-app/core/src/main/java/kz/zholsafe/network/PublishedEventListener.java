package kz.zholsafe.network;

/** Called only after ZholNet confirms an accepted/idempotent publication. */
@FunctionalInterface
public interface PublishedEventListener {
    void onPublished(String eventId);
    static PublishedEventListener none() { return eventId -> {}; }
}
