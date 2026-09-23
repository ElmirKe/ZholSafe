package kz.zholsafe.network;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Typed Stage 6.0 input for future advisory UI; no warning behavior is implemented here. */
public interface NearbyHazardProvider {
    CompletionStage<ClientResult<List<NearbyHazard>>> nearby(NearbyQuery query);
}
