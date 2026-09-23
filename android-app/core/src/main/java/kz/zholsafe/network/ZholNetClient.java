package kz.zholsafe.network;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Asynchronous client boundary. Implementations must never block the camera/inference thread. */
public interface ZholNetClient {
    CompletionStage<ClientResult<NearbyHazard>> publishHazard(NetworkHazardEvent event);
    CompletionStage<ClientResult<List<NearbyHazard>>> getNearbyHazards(NearbyQuery query);
}
