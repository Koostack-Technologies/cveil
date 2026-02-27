package com.koostack.operio.core.data.consent.invalidation;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the lifecycle of {@link InvalidationSubscriber.Subscription}s for a
 * set of client connections.
 *
 * <p>Typical usage: a transport layer (SSE endpoint, WebSocket handler) creates
 * one {@code SubscriptionManager} per server instance.  When a client connects,
 * it calls {@link #add}; when the client disconnects, {@link #removeAll} cleans
 * up every subscription for that connection.
 *
 * <p>Thread-safe for concurrent use from transport threads.
 */
public class SubscriptionManager implements AutoCloseable {

    private final InvalidationSubscriber subscriber;

    /** connectionId → set of active subscriptions for that connection. */
    private final ConcurrentHashMap<String, Set<InvalidationSubscriber.Subscription>> connections =
            new ConcurrentHashMap<>();

    // ── Metrics ─────────────────────────────────────────────────────────────
    private final AtomicLong totalSubscriptions = new AtomicLong();
    private final AtomicLong totalMessagesDelivered = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();

    public SubscriptionManager(InvalidationSubscriber subscriber) {
        this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
    }

    /**
     * Add a subscription for a client connection.
     *
     * @param connectionId unique identifier for the client connection
     * @param tenantId     the tenant to subscribe to
     * @param sink         the sink that delivers messages to the client
     * @return the subscription handle
     */
    public InvalidationSubscriber.Subscription add(String connectionId, String tenantId,
                                                    InvalidationSink sink) {
        Objects.requireNonNull(connectionId, "connectionId");

        // Wrap the sink to track metrics
        InvalidationSink metered = message -> {
            try {
                sink.send(message);
                totalMessagesDelivered.incrementAndGet();
            } catch (Exception e) {
                totalErrors.incrementAndGet();
                throw e;
            }
        };

        InvalidationSubscriber.Subscription sub = subscriber.subscribe(tenantId, metered);
        connections.computeIfAbsent(connectionId, k -> ConcurrentHashMap.newKeySet()).add(sub);
        totalSubscriptions.incrementAndGet();
        return sub;
    }

    /**
     * Remove a single subscription for a connection.
     */
    public void remove(String connectionId, InvalidationSubscriber.Subscription subscription) {
        Set<InvalidationSubscriber.Subscription> subs = connections.get(connectionId);
        if (subs != null) {
            if (subs.remove(subscription)) {
                subscription.cancel();
                totalSubscriptions.decrementAndGet();
            }
            if (subs.isEmpty()) {
                connections.remove(connectionId, subs);
            }
        }
    }

    /**
     * Remove all subscriptions for a connection (client disconnect cleanup).
     *
     * @param connectionId the connection to clean up
     * @return the number of subscriptions cancelled
     */
    public int removeAll(String connectionId) {
        Set<InvalidationSubscriber.Subscription> subs = connections.remove(connectionId);
        if (subs == null) return 0;

        int count = 0;
        for (InvalidationSubscriber.Subscription sub : subs) {
            sub.cancel();
            count++;
        }
        totalSubscriptions.addAndGet(-count);
        return count;
    }

    /** Number of active subscriptions across all connections. */
    public long activeSubscriptionCount() {
        return totalSubscriptions.get();
    }

    /** Total messages successfully delivered across all sinks. */
    public long messagesDelivered() {
        return totalMessagesDelivered.get();
    }

    /** Total sink delivery errors. */
    public long errorCount() {
        return totalErrors.get();
    }

    /** Number of active client connections. */
    public int connectionCount() {
        return connections.size();
    }

    /**
     * Close all subscriptions and the underlying subscriber.
     */
    @Override
    public void close() throws Exception {
        for (String connId : List.copyOf(connections.keySet())) {
            removeAll(connId);
        }
        subscriber.close();
    }
}
