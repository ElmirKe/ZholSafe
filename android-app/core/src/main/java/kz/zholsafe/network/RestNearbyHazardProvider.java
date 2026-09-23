package kz.zholsafe.network;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class RestNearbyHazardProvider implements NearbyHazardProvider {
    private final ZholNetClient client;
    public RestNearbyHazardProvider(ZholNetClient client) { this.client = Objects.requireNonNull(client); }
    @Override public CompletionStage<ClientResult<List<NearbyHazard>>> nearby(NearbyQuery query) {
        return client.getNearbyHazards(query);
    }
}
