package kz.zholsafe.network;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OkHttpZholNetClientTest {
    private MockWebServer server;
    private OkHttpZholNetClient client;

    @BeforeEach void setUp() throws IOException {
        server = new MockWebServer(); server.start();
        client = new OkHttpZholNetClient(new OkHttpClient(), server.url("/").toString(), new HazardJsonCodec());
    }
    @AfterEach void tearDown() throws IOException { server.shutdown(); }

    @Test void nearbyResponseParsesAndQueryUsesStage5Endpoint() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("""
                [{"eventId":"e1","schemaVersion":1,"hazardType":"HORSE","severity":"WARNING",
                "latitude":43.2,"longitude":76.9,"sourceTimestamp":"2026-09-23T12:00:00Z",
                "receivedTimestamp":"2026-09-23T12:00:01Z","expiresAt":"2026-09-23T12:30:01Z",
                "confidence":0.82,"headingDegrees":90.0,"approximateDistanceMeters":15.0,
                "ttcSeconds":3.0,"reportCount":1,"deduplicated":false}]
                """));
        ClientResult<List<NearbyHazard>> result = client.getNearbyHazards(new NearbyQuery(43.2, 76.9,
                500, null, NetworkSeverity.WARNING, Set.of(NetworkHazardType.HORSE), 10))
                .toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertTrue(result.successful());
        assertEquals(NetworkHazardType.HORSE, result.value().get(0).hazardType());
        assertTrue(server.takeRequest().getPath().startsWith("/api/v1/hazards/nearby?"));
    }

    @Test void serverErrorIsExplicitFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{}"));
        ClientResult<NearbyHazard> result = client.publishHazard(
                Stage6TestFixtures.event(Instant.now())).toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertFalse(result.successful());
        assertEquals(500, result.httpStatus());
        assertNotNull(result.error());
    }
}
