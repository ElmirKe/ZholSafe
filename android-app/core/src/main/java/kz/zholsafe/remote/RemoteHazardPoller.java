package kz.zholsafe.remote;

import kz.zholsafe.location.LocationFix;
import kz.zholsafe.location.LocationProvider;
import kz.zholsafe.network.ClientResult;
import kz.zholsafe.network.NearbyHazard;
import kz.zholsafe.network.NearbyQuery;
import kz.zholsafe.network.ZholNetClient;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Foreground lifecycle poller: one scheduler, one in-flight request, no request accumulation. */
public final class RemoteHazardPoller implements AutoCloseable {
    private final LocationProvider locations;
    private final ZholNetClient client;
    private final RemoteHazardCoordinator coordinator;
    private final RemoteHazardConfig config;
    private final Clock clock;
    private final LongSupplier elapsedClock;
    private final AtomicReference<RemoteHazardSnapshot> latest;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private ScheduledExecutorService scheduler;

    public RemoteHazardPoller(LocationProvider locations, ZholNetClient client,
            RemoteHazardCoordinator coordinator, RemoteHazardConfig config,
            Clock clock, LongSupplier elapsedClock) {
        this.locations = Objects.requireNonNull(locations); this.client = Objects.requireNonNull(client);
        this.coordinator = Objects.requireNonNull(coordinator); this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock); this.elapsedClock = Objects.requireNonNull(elapsedClock);
        latest = new AtomicReference<>(RemoteHazardSnapshot.unavailable(
                RemoteHazardSnapshot.Status.STOPPED, clock.instant(), "polling stopped"));
    }

    public synchronized void start() {
        if (scheduler != null) return;
        long activeGeneration = generation.incrementAndGet();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "zholnet-nearby-poller"); thread.setDaemon(true); return thread;
        });
        scheduler.scheduleAtFixedRate(() -> poll(activeGeneration), 0,
                config.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void poll(long activeGeneration) {
        if (generation.get() != activeGeneration || !inFlight.compareAndSet(false, true)) return;
        Optional<LocationFix> location = locations.latestFix();
        long elapsed = elapsedClock.getAsLong();
        if (location.isEmpty() || !location.get().freshAt(elapsed, config.maximumVehicleLocationAge().toNanos())) {
            if (generation.get() == activeGeneration) {
                latest.set(coordinator.accept(location, elapsed,
                        ClientResult.success(List.of(), 200)));
                inFlight.set(false);
            }
            return;
        }
        LocationFix fix = location.get();
        NearbyQuery query = new NearbyQuery(fix.latitude(), fix.longitude(), config.fetchRadiusMeters(),
                null, null, java.util.Set.of(), config.maximumResults());
        try {
            client.getNearbyHazards(query).whenComplete((result, thrown) -> {
                if (generation.get() == activeGeneration) {
                    try {
                        ClientResult<List<NearbyHazard>> outcome = thrown == null ? result
                                : ClientResult.failure(0, errorMessage(thrown));
                        latest.set(coordinator.accept(location, elapsedClock.getAsLong(), outcome));
                    } catch (RuntimeException callbackFailure) {
                        latest.set(coordinator.accept(location, elapsedClock.getAsLong(),
                                ClientResult.failure(0, errorMessage(callbackFailure))));
                    } finally {
                        if (generation.get() == activeGeneration) inFlight.set(false);
                    }
                }
            });
        } catch (RuntimeException error) {
            if (generation.get() == activeGeneration) {
                latest.set(coordinator.accept(location, elapsed,
                        ClientResult.failure(0, errorMessage(error))));
                inFlight.set(false);
            }
        }
    }

    public RemoteHazardSnapshot latest() { return latest.get(); }
    public boolean requestInFlight() { return inFlight.get(); }

    public synchronized void stop() {
        generation.incrementAndGet(); inFlight.set(false);
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        latest.set(RemoteHazardSnapshot.unavailable(RemoteHazardSnapshot.Status.STOPPED,
                clock.instant(), "polling stopped"));
    }
    private static String errorMessage(Throwable error) {
        return error.getClass().getSimpleName() + ": "
                + (error.getMessage() == null ? "network request failed" : error.getMessage());
    }
    @Override public void close() { stop(); }
}
