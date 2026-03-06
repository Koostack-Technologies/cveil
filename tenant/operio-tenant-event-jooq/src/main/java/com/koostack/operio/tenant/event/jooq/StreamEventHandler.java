package com.koostack.operio.tenant.event.jooq;

import java.util.Map;

/**
 * Callback interface for processing tenant domain events consumed from Redis Streams by {@link
 * TenantStreamConsumer}.
 *
 * <p>Implementations handle the deserialization and projection logic for specific use cases (e.g.,
 * SQL admin projection, Redis decision projection).
 *
 * <p>Implementations <strong>must</strong> be thread-safe — a single instance is invoked
 * concurrently from multiple shard-reader threads.
 */
@FunctionalInterface
public interface StreamEventHandler {

    /**
     * Processes a single event entry from a Redis Stream.
     *
     * <p>The {@code fields} map contains the same keys published by {@link OutboxPoller}: {@code
     * eventId}, {@code tenantId}, {@code aggregateId}, {@code aggregateType}, {@code eventType},
     * {@code version}, and {@code payload}.
     *
     * <p>If this method throws, the consumer will log the error and acknowledge the message to
     * prevent stream blocking (poison-message safety). Implementations should handle their own
     * retries if needed.
     *
     * @param streamKey the Redis stream key the event was read from
     * @param messageId the Redis stream entry ID (e.g. {@code "1234567890-0"})
     * @param fields the event fields map
     */
    void onEvent(String streamKey, String messageId, Map<String, String> fields);
}
