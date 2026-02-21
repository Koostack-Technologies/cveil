package com.koostack.operio.plugin.redis.common;

import redis.clients.jedis.JedisPooled;

/**
 * High-level factory that creates a fully initialized {@link JedisPooled} client for a Redis-backed plugin.
 * <p>
 * Handles connection setup in one call, branching on whether authentication is required.
 */
public final class RedisContextFactory {

    private RedisContextFactory() {
        // Utility class — not meant to be instantiated
    }

    /**
     * Creates a ready-to-use {@link JedisPooled} client from the given plugin config.
     * <p>
     * If the config provides a non-null, non-blank password, the client is
     * created with authentication. Otherwise, it connects without credentials.
     *
     * @param config the plugin's Redis configuration (host, port, password)
     * @return a configured {@link JedisPooled} instance
     */
    public static JedisPooled createClient(RedisPluginConfig config) {
        if (config.password() != null && !config.password().isBlank()) {
            return RedisClientFactory.createClient(config.host(), config.port(), config.password());
        }
        return RedisClientFactory.createClient(config.host(), config.port());
    }
}
