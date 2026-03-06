package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.redis.common.RedisPluginConfig;

/** Minimal {@link RedisPluginConfig} for test use. */
record TestRedisConfig(String host, int port, String password) implements RedisPluginConfig {}
