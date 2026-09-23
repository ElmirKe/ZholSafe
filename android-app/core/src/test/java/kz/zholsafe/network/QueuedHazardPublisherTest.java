package kz.zholsafe.network;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QueuedHazardPublisherTest {
    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    @Test void staleQueuedEventRejected() {
        try (QueuedHazardPublisher publisher = publisher(new HangingClient(), 2, 3)) {
            assertEquals(QueuedHazardPublisher.EnqueueResult.REJECTED_STALE,
                    publisher.enqueue(Stage6TestFixtures.event(NOW.minusSeconds(121))));
            assertEquals(0, publisher.queuedCount());
        }
    }

    @Test void queueRemainsBoundedAndDropsOldestDeterministically() {
        try (QueuedHazardPublisher publisher = publisher(new HangingClient(), 2, 3)) {
            publisher.enqueue(event("one"));
            publisher.enqueue(event("two"));
            assertEquals(QueuedHazardPublisher.EnqueueResult.ACCEPTED_DROPPED_OLDEST,
                    publisher.enqueue(event("three")));
            assertTrue(publisher.queuedCount() <= 2);
        }
    }

    @Test void httpFailureRetriesOnlyConfiguredAttemptsAndCannotChangeLocalResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch attempts = new CountDownLatch(3);
        ZholNetClient failing = new ZholNetClient() {
            @Override public CompletionStage<ClientResult<NearbyHazard>> publishHazard(NetworkHazardEvent event) {
                calls.incrementAndGet(); attempts.countDown();
                return CompletableFuture.completedFuture(ClientResult.failure(503, "offline"));
            }
            @Override public CompletionStage<ClientResult<List<NearbyHazard>>> getNearbyHazards(NearbyQuery query) {
                return CompletableFuture.completedFuture(ClientResult.failure(503, "offline"));
            }
        };
        var local = Stage6TestFixtures.warning(20 * Stage6TestFixtures.SECOND,
                kz.zholsafe.model.ObjectClass.HORSE).risk();
        try (QueuedHazardPublisher publisher = publisher(failing, 2, 3)) {
            publisher.enqueue(event("retry"));
            assertTrue(attempts.await(2, TimeUnit.SECONDS));
            assertEquals(3, calls.get());
            for (int i = 0; i < 20 && publisher.lastFailure().isEmpty(); i++) Thread.sleep(5);
            assertEquals("offline", publisher.lastFailure().orElseThrow());
            assertTrue(local.available());
        }
    }

    @Test void successfulPublicationNotifiesSelfEventMemory() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> id = new java.util.concurrent.atomic.AtomicReference<>();
        ZholNetClient success = new ZholNetClient() {
            @Override public CompletionStage<ClientResult<NearbyHazard>> publishHazard(NetworkHazardEvent event) {
                return CompletableFuture.completedFuture(ClientResult.success(null, 201));
            }
            @Override public CompletionStage<ClientResult<List<NearbyHazard>>> getNearbyHazards(NearbyQuery query) {
                return CompletableFuture.completedFuture(ClientResult.success(List.of(), 200));
            }
        };
        try (QueuedHazardPublisher publisher = new QueuedHazardPublisher(success,
                new RetryQueueConfig(2, 1, Duration.ZERO, Duration.ofMinutes(2)),
                Clock.fixed(NOW, ZoneOffset.UTC), eventId -> { id.set(eventId); registered.countDown(); })) {
            publisher.enqueue(event("published-id"));
            assertTrue(registered.await(1, TimeUnit.SECONDS));
            assertEquals("published-id", id.get());
        }
    }

    private static QueuedHazardPublisher publisher(ZholNetClient client, int capacity, int attempts) {
        return new QueuedHazardPublisher(client, new RetryQueueConfig(capacity, attempts,
                Duration.ZERO, Duration.ofMinutes(2)), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private static NetworkHazardEvent event(String id) {
        NetworkHazardEvent e = Stage6TestFixtures.event(NOW);
        return new NetworkHazardEvent(id, e.schemaVersion(), e.vehicleId(), e.hazardType(), e.severity(),
                e.confidence(), e.risk(), e.latitude(), e.longitude(), e.timestamp(), e.status(),
                e.headingDegrees(), e.approximateDistanceMeters(), e.ttcSeconds());
    }
    private static final class HangingClient implements ZholNetClient {
        @Override public CompletionStage<ClientResult<NearbyHazard>> publishHazard(NetworkHazardEvent event) {
            return new CompletableFuture<>();
        }
        @Override public CompletionStage<ClientResult<List<NearbyHazard>>> getNearbyHazards(NearbyQuery query) {
            return new CompletableFuture<>();
        }
    }
}
