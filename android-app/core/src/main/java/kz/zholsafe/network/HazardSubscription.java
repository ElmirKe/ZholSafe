package kz.zholsafe.network;

import java.util.function.Consumer;

/** Stage 6.1 extension point. Stage 6.0 uses authoritative REST nearby polling. */
public interface HazardSubscription extends AutoCloseable {
    void start(Consumer<NearbyHazard> listener);
    @Override void close();
}
