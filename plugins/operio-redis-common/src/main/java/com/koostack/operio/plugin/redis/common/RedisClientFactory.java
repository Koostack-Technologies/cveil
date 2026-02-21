package com.koostack.operio.plugin.redis.common;

import redis.clients.jedis.JedisPooled;

/**
 * Factory for creating configured {@link JedisPooled} instances
 * for use with Redis-based Operio plugins.
 */
public final class RedisClientFactory {

    private RedisClientFactory() {
        // Utility class — not meant to be instantiated
    }

    /**
     * Creates a new {@link JedisPooled} client connected to the given host and port.
     *
     * @param host the Redis server hostname
     * @param port the Redis server port
     * @return a configured {@link JedisPooled} instance
     */
    public static JedisPooled createClient(String host, int port) {
        return new JedisPooled(host, port);
    }

    /**
     * Creates a new {@link JedisPooled} client connected to the given host, port,
     * and authenticated with a password.
     *
     * @param host     the Redis server hostname
     * @param port     the Redis server port
     * @param password the Redis server password
     * @return a configured {@link JedisPooled} instance
     */
    public static JedisPooled createClient(String host, int port, String password) {
        return new JedisPooled(host, port, null, password);
    }
}
