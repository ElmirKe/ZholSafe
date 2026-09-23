package kz.zholsafe.network;

/** Application-scoped random token used only for conservative server deduplication. */
public interface AnonymousSourceIdProvider {
    String current();
    String rotate();
}
