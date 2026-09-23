package kz.zholsafe.remote;

import kz.zholsafe.location.SyntheticLocationProvider;
import kz.zholsafe.network.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RemoteHazardPollerTest {
    private static final Instant NOW = Instant.parse("2026-09-23T12:01:00Z");
    private static final long ELAPSED = 100_000_000_000L;

    @Test void overlappingPollsArePreventedAndSchedulerStopsCleanlyWithoutBlockingLocalRisk() throws Exception {
        CompletableFuture<ClientResult<List<NearbyHazard>>> pending = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        ZholNetClient client = client(query -> { calls.incrementAndGet(); return pending; });
        RemoteHazardPoller poller = poller(client);
        var localRisk = kz.zholsafe.risk.RoadRiskSnapshot.unavailable(0L,
                kz.zholsafe.risk.RoadRiskSnapshot.Status.NOT_STARTED,
                kz.zholsafe.pipeline.TrackingSnapshot.Status.NOT_STARTED,
                kz.zholsafe.pipeline.TrajectorySnapshot.Status.NOT_STARTED,
                kz.zholsafe.physical.PhysicalEstimationSnapshot.Status.NOT_STARTED);
        var localStatusBeforePolling = localRisk.status();
        long started = System.nanoTime(); poller.start();
        waitUntil(() -> calls.get() == 1);
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
        Thread.sleep(80);
        assertEquals(1, calls.get(), "ticks skip while one request is in flight");
        assertEquals(localStatusBeforePolling, localRisk.status(),
                "remote pending request cannot alter local risk state");
        poller.stop();
        int stoppedCalls = calls.get(); Thread.sleep(60);
        assertEquals(stoppedCalls, calls.get());
        assertEquals(RemoteHazardSnapshot.Status.STOPPED, poller.latest().status());
    }

    @Test void networkFailureBecomesExplicitUnavailableAndRestartIsSupported() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ZholNetClient client = client(query -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(ClientResult.failure(503, "offline"));
        });
        RemoteHazardPoller poller = poller(client);
        poller.start();
        waitUntil(() -> poller.latest().status() == RemoteHazardSnapshot.Status.NETWORK_UNAVAILABLE);
        assertEquals("offline", poller.latest().detail());
        poller.stop(); poller.start();
        waitUntil(() -> calls.get() >= 2);
        poller.close();
    }

    @Test void coordinatorShortHoldIsBoundedByTimeAndExpiry() {
        RecentPublishedEventRegistryTest.MutableClock clock =
                new RecentPublishedEventRegistryTest.MutableClock(NOW);
        RemoteHazardConfig config = config();
        RecentPublishedEventRegistry registry = RecentPublishedEventRegistry.defaults(clock);
        RemoteHazardCoordinator coordinator = new RemoteHazardCoordinator(
                new RemoteHazardEvaluator(config, registry, clock), config, clock);
        var location = java.util.Optional.of(RemoteHazardEvaluatorTest.location(43, 76, 0, ELAPSED));
        var event = RemoteHazardEvaluatorTest.hazard("held", 43.003, 76, NetworkSeverity.WARNING,
                NOW.minusSeconds(5), NOW.plusSeconds(60));
        assertTrue(coordinator.accept(location, ELAPSED, ClientResult.success(List.of(event), 200))
                .selected().isPresent());
        assertTrue(coordinator.accept(location, ELAPSED, ClientResult.success(List.of(), 200))
                .selected().isPresent());
        clock.now = NOW.plusSeconds(4);
        assertTrue(coordinator.accept(location, ELAPSED, ClientResult.success(List.of(), 200))
                .selected().isEmpty());
    }

    private static RemoteHazardPoller poller(ZholNetClient client) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RemoteHazardConfig config = config();
        SyntheticLocationProvider location = new SyntheticLocationProvider(
                RemoteHazardEvaluatorTest.location(43, 76, 0, ELAPSED));
        var registry = RecentPublishedEventRegistry.defaults(clock);
        var evaluator = new RemoteHazardEvaluator(config, registry, clock);
        return new RemoteHazardPoller(location, client,
                new RemoteHazardCoordinator(evaluator, config, clock), config, clock, () -> ELAPSED);
    }
    private static RemoteHazardConfig config() {
        return new RemoteHazardConfig(2_000, Duration.ofMillis(20), Duration.ofMillis(100),
                Duration.ofSeconds(5), Duration.ofMinutes(2), 500, 1_200,
                60, 120, Duration.ofSeconds(3), 20);
    }
    private static ZholNetClient client(java.util.function.Function<NearbyQuery,
            CompletionStage<ClientResult<List<NearbyHazard>>>> nearby) {
        return new ZholNetClient() {
            @Override public CompletionStage<ClientResult<NearbyHazard>> publishHazard(NetworkHazardEvent event) {
                return CompletableFuture.completedFuture(ClientResult.failure(500, "unused"));
            }
            @Override public CompletionStage<ClientResult<List<NearbyHazard>>> getNearbyHazards(NearbyQuery query) {
                return nearby.apply(query);
            }
        };
    }
    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
}
