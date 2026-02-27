package com.koostack.operio.core.data.consent.invalidation;

import java.util.List;

/**
 * Publishes cache invalidation messages to a transport (e.g., Redis Pub/Sub).
 *
 * <p>The projection handler depends only on this abstraction.  In production
 * the implementation is backed by Redis Pub/Sub; in tests a no-op or
 * in-memory stub can be used.
 *
 * <p>{@code messageId} generation is the caller's responsibility — use
 * {@link java.util.UUID#randomUUID()} per message.  This keeps the publisher
 * stateless and testable.
 */
public interface InvalidationPublisher extends AutoCloseable {

    /**
     * Publish a single invalidation message.
     */
    void publish(CacheInvalidationMessage message);

    /**
     * Publish a batch of invalidation messages.  Implementations should
     * optimise for a single network round-trip where possible.
     */
    void publishBatch(List<CacheInvalidationMessage> messages);

    /** Default no-op close — caller owns underlying resource lifecycles. */
    @Override
    default void close() {}
}
