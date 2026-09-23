package kz.zholsafe.network;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory implementation for demos/tests; it has no hardware-identifier dependency. */
public final class RandomAnonymousSourceIdProvider implements AnonymousSourceIdProvider {
    private final AtomicReference<String> value = new AtomicReference<>(newToken());
    @Override public String current() { return value.get(); }
    @Override public String rotate() { String next = newToken(); value.set(next); return next; }
    private static String newToken() { return "anon-" + UUID.randomUUID(); }
}
