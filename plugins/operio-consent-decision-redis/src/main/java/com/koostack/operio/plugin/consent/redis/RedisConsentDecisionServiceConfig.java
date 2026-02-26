package com.koostack.operio.plugin.consent.redis;

import com.koostack.operio.plugin.redis.common.RedisPluginConfig;

public record RedisConsentDecisionServiceConfig(
    String host,
    int port,
    String password
) implements RedisPluginConfig {
    /**
     * Convenience constructor for connections without authentication.
     */
    public RedisConsentDecisionServiceConfig(String host, int port) {
        this(host, port, null);
    }
}
