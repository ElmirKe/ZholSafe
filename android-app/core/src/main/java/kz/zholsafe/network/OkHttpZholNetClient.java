package kz.zholsafe.network;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** OkHttp asynchronous implementation; no execute()/blocking network call is used. */
public final class OkHttpZholNetClient implements ZholNetClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http;
    private final HttpUrl baseUrl;
    private final HazardJsonCodec codec;

    public OkHttpZholNetClient(OkHttpClient http, String baseUrl, HazardJsonCodec codec) {
        this.http = Objects.requireNonNull(http);
        this.baseUrl = Objects.requireNonNull(HttpUrl.parse(baseUrl), "invalid baseUrl");
        this.codec = Objects.requireNonNull(codec);
    }

    @Override public CompletionStage<ClientResult<NearbyHazard>> publishHazard(NetworkHazardEvent event) {
        Request request = new Request.Builder().url(baseUrl.newBuilder().addPathSegments("api/v1/hazards").build())
                .post(RequestBody.create(codec.encode(event), JSON)).build();
        return enqueue(request, body -> codec.decodeOne(body));
    }

    @Override public CompletionStage<ClientResult<List<NearbyHazard>>> getNearbyHazards(NearbyQuery q) {
        HttpUrl.Builder url = baseUrl.newBuilder().addPathSegments("api/v1/hazards/nearby")
                .addQueryParameter("latitude", Double.toString(q.latitude()))
                .addQueryParameter("longitude", Double.toString(q.longitude()))
                .addQueryParameter("radiusMeters", Double.toString(q.radiusMeters()));
        if (q.since() != null) url.addQueryParameter("since", q.since().toString());
        if (q.minimumSeverity() != null) url.addQueryParameter("minimumSeverity", q.minimumSeverity().name());
        q.eventTypes().stream().sorted().forEach(t -> url.addQueryParameter("eventTypes", t.name()));
        if (q.limit() != null) url.addQueryParameter("limit", q.limit().toString());
        return enqueue(new Request.Builder().url(url.build()).get().build(), codec::decodeNearby);
    }

    private <T> CompletionStage<ClientResult<T>> enqueue(Request request, Decoder<T> decoder) {
        CompletableFuture<ClientResult<T>> future = new CompletableFuture<>();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                future.complete(ClientResult.failure(0, error.getClass().getSimpleName() + ": " + error.getMessage()));
            }
            @Override public void onResponse(Call call, Response response) {
                try (response) {
                    String body = response.body() == null ? "" : response.body().string();
                    if (!response.isSuccessful()) {
                        future.complete(ClientResult.failure(response.code(), "ZholNet HTTP " + response.code()));
                    } else {
                        try { future.complete(ClientResult.success(decoder.decode(body), response.code())); }
                        catch (RuntimeException e) { future.complete(ClientResult.failure(response.code(), "Invalid ZholNet response")); }
                    }
                } catch (IOException e) {
                    future.complete(ClientResult.failure(0, "Response read failed"));
                }
            }
        });
        return future;
    }
    private interface Decoder<T> { T decode(String body); }
}
