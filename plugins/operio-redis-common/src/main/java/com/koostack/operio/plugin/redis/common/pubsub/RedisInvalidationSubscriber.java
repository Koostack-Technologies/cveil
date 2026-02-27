package com.koostack.operio.plugin.redis.common.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.data.consent.invalidation.CacheInvalidationMessage;
import com.koostack.operio.core.data.consent.invalidation.InvalidationSink;
import com.koostack.operio.core.data.consent.invalidation.InvalidationSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis Pub/Sub-backed {@link InvalidationSubscriber}.
 *
 * <p><b>Threading model:</b> A single dedicated daemon thread runs the blocking
 * {@link JedisPubSub#subscribe} call.  Dynamic channel subscribe/unsubscribe is
 * performed via {@link JedisPubSub#subscribe(String...)} and
 * {@link JedisPubSub#unsubscribe(String...)} which are thread-safe and signal
 * the blocking read loop.
 *
 * <p><b>Bootstrap:</b> A sentinel control channel
 * ({@code operio:consent-cache-invalidation:__control__}) is always subscribed
 * to avoid the chicken-and-egg problem of starting the blocking thread with no
 * channels.
 *
 * <p><b>Reconnection:</b> If the subscriber thread dies (Redis disconnect), it
 * re-connects with exponential backoff (1s → 2s → 4s → max 30s) and
 * re-subscribes all channels that have active sinks.
 *
 * <p><b>Important:</b> The {@code adminJedis} connection must be an admin pool,
 * not a tenant-scoped pool.  Tenant ACL users lack {@code &channels} permissions
 * and {@code SUBSCRIBE} will fail with {@code NOPERM}.
 */
public class RedisInvalidationSubscriber implements InvalidationSubscriber {

    private static final Logger log = LoggerFactory.getLogger(RedisInvalidationSubscriber.class);
    private static final String CHANNEL_PREFIX = RedisInvalidationPublisher.CHANNEL_PREFIX;
    static final String CONTROL_CHANNEL = CHANNEL_PREFIX + "__control__";

    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final int MAX_CONSECUTIVE_SINK_ERRORS = 5;

    private final JedisPooled adminJedis;
    private final ObjectMapper objectMapper;

    /** Exponential backoff for reconnection, reset on successful subscribe. */
    private final AtomicLong backoffMs = new AtomicLong(INITIAL_BACKOFF_MS);

    /** channel → set of sinks registered for that channel. */
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<SinkEntry>> channelSinks =
            new ConcurrentHashMap<>();

    /** Tracks consecutive send() failures per sink for error isolation. */
    private final ConcurrentHashMap<SinkEntry, Integer> sinkErrorCounts =
            new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch subscribedLatch = new CountDownLatch(1);

    private volatile InternalPubSub pubSubHandler;
    private volatile Thread subscriberThread;

    public RedisInvalidationSubscriber(JedisPooled adminJedis, ObjectMapper objectMapper) {
        this.adminJedis = Objects.requireNonNull(adminJedis, "adminJedis");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        startSubscriberThread();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    @Override
    public Subscription subscribe(String tenantId, InvalidationSink sink) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sink, "sink");
        if (closed.get()) {
            throw new IllegalStateException("Subscriber is closed");
        }

        String channel = channelFor(tenantId);
        SinkEntry entry = new SinkEntry(tenantId, sink);

        CopyOnWriteArraySet<SinkEntry> sinks = channelSinks.computeIfAbsent(
                channel, k -> new CopyOnWriteArraySet<>());

        // Two threads may both see isEmpty()==true and call handler.subscribe(channel).
        // This is benign: JedisPubSub deduplicates, and getActiveChannels() on reconnect
        // is the source of truth for subscribed channels.
        boolean firstForChannel = sinks.isEmpty();
        sinks.add(entry);

        // If this is the first sink for this channel, subscribe on the Redis thread
        if (firstForChannel) {
            awaitSubscribed();
            JedisPubSub handler = this.pubSubHandler;
            if (handler != null && handler.isSubscribed()) {
                handler.subscribe(channel);
                log.debug("Subscribed to Redis channel {}", channel);
            }
        }

        return new SubscriptionImpl(entry, channel);
    }

    @Override
    public void unsubscribe(Subscription subscription) {
        subscription.cancel();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;

        JedisPubSub handler = this.pubSubHandler;
        if (handler != null && handler.isSubscribed()) {
            try {
                handler.unsubscribe();
            } catch (Exception e) {
                log.debug("Error during unsubscribe on close", e);
            }
        }

        Thread t = this.subscriberThread;
        if (t != null) {
            t.interrupt();
        }

        channelSinks.clear();
        sinkErrorCounts.clear();
        log.info("RedisInvalidationSubscriber closed");
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private void startSubscriberThread() {
        pubSubHandler = new InternalPubSub();
        subscriberThread = Thread.ofPlatform()
                .daemon(true)
                .name("operio-invalidation-subscriber")
                .start(() -> runSubscribeLoop());
    }

    private void runSubscribeLoop() {
        while (!closed.get()) {
            try {
                pubSubHandler = new InternalPubSub();
                log.info("Starting Redis Pub/Sub subscription on control channel");

                // Collect channels to subscribe: control + any with active sinks
                String[] channels = getActiveChannels();

                // This call blocks until unsubscribe() or disconnect
                adminJedis.subscribe(pubSubHandler, channels);

                // If we get here normally (unsubscribe was called), exit loop
                if (closed.get()) break;

                log.warn("Redis Pub/Sub subscription ended unexpectedly, reconnecting...");

            } catch (Exception e) {
                if (closed.get()) break;
                long currentBackoff = backoffMs.get();
                log.error("Redis Pub/Sub subscriber thread error, reconnecting in {}ms", currentBackoff, e);

                try {
                    Thread.sleep(currentBackoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoffMs.set(Math.min(currentBackoff * 2, MAX_BACKOFF_MS));
            }
        }
        subscribedLatch.countDown(); // ensure any waiters unblock on close
        log.debug("Redis Pub/Sub subscriber thread exiting");
    }

    private String[] getActiveChannels() {
        String[] active = channelSinks.keySet().toArray(new String[0]); // snapshot
        String[] channels = new String[active.length + 1];
        channels[0] = CONTROL_CHANNEL;
        System.arraycopy(active, 0, channels, 1, active.length);
        return channels;
    }

    private void awaitSubscribed() {
        try {
            if (!subscribedLatch.await(5, TimeUnit.SECONDS)) {
                log.warn("Timed out waiting for subscriber thread to start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void removeSink(SinkEntry entry, String channel) {
        CopyOnWriteArraySet<SinkEntry> sinks = channelSinks.get(channel);
        if (sinks == null) return;

        sinks.remove(entry);
        sinkErrorCounts.remove(entry);

        // If last sink for this channel, unsubscribe from Redis
        if (sinks.isEmpty()) {
            channelSinks.remove(channel, sinks);
            JedisPubSub handler = this.pubSubHandler;
            if (handler != null && handler.isSubscribed() && !channel.equals(CONTROL_CHANNEL)) {
                try {
                    handler.unsubscribe(channel);
                    log.debug("Unsubscribed from Redis channel {} (no remaining sinks)", channel);
                } catch (Exception e) {
                    log.debug("Error unsubscribing from channel {}", channel, e);
                }
            }
        }
    }

    static String channelFor(String tenantId) {
        return CHANNEL_PREFIX + tenantId;
    }

    // ── JedisPubSub handler ─────────────────────────────────────────────────

    private class InternalPubSub extends JedisPubSub {

        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            backoffMs.set(INITIAL_BACKOFF_MS);
            subscribedLatch.countDown();
            log.debug("Subscribed to channel {} (total: {})", channel, subscribedChannels);
        }

        @Override
        public void onUnsubscribe(String channel, int subscribedChannels) {
            log.debug("Unsubscribed from channel {} (total: {})", channel, subscribedChannels);
        }

        @Override
        public void onMessage(String channel, String message) {
            if (CONTROL_CHANNEL.equals(channel)) return;

            CacheInvalidationMessage parsed;
            try {
                parsed = objectMapper.readValue(message, CacheInvalidationMessage.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize invalidation message on channel {}", channel, e);
                return;
            }

            CopyOnWriteArraySet<SinkEntry> sinks = channelSinks.get(channel);
            if (sinks == null || sinks.isEmpty()) return;

            for (SinkEntry entry : sinks) {
                try {
                    entry.sink().send(parsed);
                    sinkErrorCounts.remove(entry);
                } catch (Exception e) {
                    int count = sinkErrorCounts.merge(entry, 1, Integer::sum);
                    log.warn("Sink send() failed on channel {} (consecutive errors: {})",
                            channel, count, e);
                    if (count >= MAX_CONSECUTIVE_SINK_ERRORS) {
                        log.warn("Removing persistently-failing sink from channel {} after {} errors",
                                channel, count);
                        removeSink(entry, channel);
                    }
                }
            }
        }
    }

    // ── Subscription handle ─────────────────────────────────────────────────

    private class SubscriptionImpl implements Subscription {

        private final SinkEntry entry;
        private final String channel;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        SubscriptionImpl(SinkEntry entry, String channel) {
            this.entry = entry;
            this.channel = channel;
        }

        @Override
        public String tenantId() {
            return entry.tenantId();
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                removeSink(entry, channel);
            }
        }
    }

    // ── Sink entry (identity by UUID for CopyOnWriteArraySet) ───────────────

    private record SinkEntry(String tenantId, InvalidationSink sink, String id) {
        SinkEntry(String tenantId, InvalidationSink sink) {
            this(tenantId, sink, UUID.randomUUID().toString());
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof SinkEntry s && id.equals(s.id));
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }
}
