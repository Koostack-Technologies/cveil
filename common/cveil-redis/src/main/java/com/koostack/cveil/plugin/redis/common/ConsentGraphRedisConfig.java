package com.koostack.cveil.plugin.redis.common;

/** Configuration for the graph-based consent decision + graph query service. */
public record ConsentGraphRedisConfig(String host, int port, String password)
        implements RedisPluginConfig {

    public ConsentGraphRedisConfig(String host, int port) {
        this(host, port, null);
    }
}
