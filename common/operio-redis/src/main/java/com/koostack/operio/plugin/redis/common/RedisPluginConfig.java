package com.koostack.operio.plugin.redis.common;

import com.koostack.operio.core.spi.PluginConfig;

/**
 * Common configuration interface for all Redis-backed Operio plugins.
 * <p>
 * Plugin-specific config records should implement this interface
 * rather than {@link PluginConfig} directly, ensuring a consistent
 * set of connection parameters across all Redis plugins.
 */
public interface RedisPluginConfig extends PluginConfig {

    /**
     * @return the Redis server hostname
     */
    String host();

    /**
     * @return the Redis server port
     */
    int port();

    /**
     * @return the Redis server password, or {@code null} if no authentication is required
     */
    String password();

    /**
     * @return optional TLS configuration for encrypted connections,
     *         or {@code null} if TLS is not configured
     */
    default RedisClientFactory.TlsConfig tlsConfig() {
        return null;
    }
}
