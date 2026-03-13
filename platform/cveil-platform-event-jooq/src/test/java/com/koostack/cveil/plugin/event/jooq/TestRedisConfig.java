package com.koostack.cveil.plugin.event.jooq;

import com.koostack.cveil.plugin.redis.common.RedisPluginConfig;

/** Minimal {@link RedisPluginConfig} for test use. */
record TestRedisConfig(String host, int port, String password) implements RedisPluginConfig {}
