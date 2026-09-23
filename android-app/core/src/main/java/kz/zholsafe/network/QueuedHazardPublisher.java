package kz.zholsafe.network;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Metadata-only bounded queue. One worker and at most one scheduled retry prevent thread/task
 * growth. Full queues deterministically drop the oldest event; stale events are never uploaded.
 */
public final class QueuedHazardPublisher implements AutoCloseable {
    public enum EnqueueResult { ACCEPTED, ACCEPTED_DROPPED_OLDEST, REJECTED_FULL_IN_FLIGHT,
        REJECTED_STALE, CLOSED }
    private record Pending(NetworkHazardEvent event, int attempts) {}

    private final ZholNetClient client;
    private final RetryQueueConfig config;
    private final Clock clock;
    private final ScheduledExecutorService worker;
    private final Deque<Pending> queue = new ArrayDeque<>();
    private boolean running;
    private boolean closed;
    private String lastFailure;

    public QueuedHazardPublisher(ZholNetClient client, RetryQueueConfig config, Clock clock) {
        this(client, config, clock, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zholnet-publisher"); t.setDaemon(true); return t;
        }));
    }

    QueuedHazardPublisher(ZholNetClient client, RetryQueueConfig config, Clock clock,
                          ScheduledExecutorService worker) {
        this.client = Objects.requireNonNull(client); this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock); this.worker = Objects.requireNonNull(worker);
    }

    public synchronized EnqueueResult enqueue(NetworkHazardEvent event) {
        Objects.requireNonNull(event);
        if (closed) return EnqueueResult.CLOSED;
        if (stale(event, clock.instant())) return EnqueueResult.REJECTED_STALE;
        EnqueueResult result = EnqueueResult.ACCEPTED;
        if (queue.size() >= config.capacity()) {
            if (running && queue.size() == 1) return EnqueueResult.REJECTED_FULL_IN_FLIGHT;
            var iterator = queue.iterator();
            iterator.next();
            if (running) iterator.next();
            iterator.remove();
            result = EnqueueResult.ACCEPTED_DROPPED_OLDEST;
        }
        queue.addLast(new Pending(event, 0));
        startIfIdle(Duration.ZERO);
        return result;
    }

    public synchronized int queuedCount() { return queue.size(); }
    /** Latest terminal upload diagnostic for telemetry/logging; never thrown into local processing. */
    public synchronized Optional<String> lastFailure() { return Optional.ofNullable(lastFailure); }

    private synchronized void startIfIdle(Duration delay) {
        if (running || closed || queue.isEmpty()) return;
        running = true;
        worker.schedule(this::sendHead, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void sendHead() {
        Pending pending;
        synchronized (this) {
            if (closed || queue.isEmpty()) { running = false; return; }
            pending = queue.peekFirst();
            if (stale(pending.event(), clock.instant())) {
                queue.removeFirst(); running = false; startIfIdle(Duration.ZERO); return;
            }
        }
        client.publishHazard(pending.event()).whenComplete((result, thrown) -> complete(pending, result, thrown));
    }

    private synchronized void complete(Pending sent, ClientResult<NearbyHazard> result, Throwable thrown) {
        if (closed || queue.isEmpty() || queue.peekFirst() != sent) { running = false; return; }
        queue.removeFirst();
        boolean failed = thrown != null || result == null || !result.successful();
        if (failed && sent.attempts() + 1 < config.maximumAttempts()
                && !stale(sent.event(), clock.instant())) {
            queue.addFirst(new Pending(sent.event(), sent.attempts() + 1));
            running = false;
            long multiplier = 1L << Math.min(sent.attempts(), 20);
            startIfIdle(config.initialBackoff().multipliedBy(multiplier));
        } else {
            if (failed) {
                lastFailure = thrown != null ? thrown.getClass().getSimpleName() + ": " + thrown.getMessage()
                        : result == null ? "missing client result" : result.error();
            }
            running = false;
            startIfIdle(Duration.ZERO);
        }
    }

    private boolean stale(NetworkHazardEvent event, Instant now) {
        Duration age = Duration.between(event.timestamp(), now);
        return age.isNegative() || age.compareTo(config.maximumEventAge()) > 0;
    }

    @Override public synchronized void close() {
        closed = true; queue.clear(); worker.shutdownNow();
    }
}
