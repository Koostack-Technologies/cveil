package com.koostack.cveil.plugin.redis.common.stream;

import java.util.Map;

/**
 * Callback interface for processing entries consumed from Redis Streams by {@link
 * AbstractStreamConsumer}.
 *
 * <p>Implementations handle deserialization and projection logic for specific use cases (e.g. event
 * projection, metric projection).
 *
 * <p>Implementations <strong>must</strong> be thread-safe — a single instance is invoked
 * concurrently from multiple consumer threads.
 */
@FunctionalInterface
public interface StreamHandler {

    /**
     * Processes a single entry from a Redis Stream.
     *
     * <p>If this method throws, the consumer will log the error and acknowledge the message to
     * prevent stream blocking (poison-message safety). Implementations should handle their own
     * retries if needed.
     *
     * @param streamKey the Redis stream key the entry was read from
     * @param messageId the Redis stream entry ID (e.g. {@code "1234567890-0"})
     * @param fields the entry fields map
     */
    void onEvent(String streamKey, String messageId, Map<String, String> fields);
}
