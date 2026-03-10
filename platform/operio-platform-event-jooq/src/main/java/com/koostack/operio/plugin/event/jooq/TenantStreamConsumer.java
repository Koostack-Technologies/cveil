package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.stream.AbstractStreamConsumer;
import com.koostack.operio.plugin.redis.common.stream.StreamHandler;

/**
 * Per-tenant <strong>event</strong> stream consumer.
 *
 * <p>Extends {@link AbstractStreamConsumer} with the event stream key pattern ({@code
 * operio:stream:tenant:{tenantId}}).
 *
 * <p>Existing callers that register a {@link StreamEventHandler} continue to work — the handler is
 * adapted to the shared {@link StreamHandler} contract automatically.
 *
 * @see AbstractStreamConsumer
 */
public class TenantStreamConsumer extends AbstractStreamConsumer {

    private static final String STREAM_KEY_PREFIX = "operio:stream:tenant:";

    /**
     * @param jedisProvider Redis connection provider (shared/admin pool)
     * @param blockMs XREADGROUP block timeout in milliseconds (must fit in int)
     * @param batchSize max entries per XREADGROUP call
     * @throws IllegalArgumentException if blockMs is negative or exceeds Integer.MAX_VALUE
     */
    public TenantStreamConsumer(JedisProvider jedisProvider, long blockMs, int batchSize) {
        super(jedisProvider, blockMs, batchSize);
    }

    @Override
    protected String streamKeyForTenant(String tenantId) {
        return STREAM_KEY_PREFIX + tenantId;
    }

    /**
     * Registers a {@link StreamEventHandler} with its consumer group name. Must be called before
     * any tenants are added.
     *
     * <p>This overload adapts the legacy {@link StreamEventHandler} to the shared {@link
     * StreamHandler} interface.
     *
     * @param groupName the Redis consumer group name
     * @param handler the event handler
     */
    public void registerHandler(String groupName, StreamEventHandler handler) {
        super.registerHandler(groupName, (StreamHandler) handler::onEvent);
    }
}
