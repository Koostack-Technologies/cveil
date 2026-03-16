package com.koostack.cveil.platform.metrics.redis;

import com.koostack.cveil.plugin.redis.common.RedisPluginConfig;
import javax.sql.DataSource;
import redis.clients.jedis.JedisPooled;

/**
 * Configuration for the platform metrics Redis implementation.
 *
 * @param redisConfig Redis connection configuration
 * @param dataSource the platform database data source for telemetry projection
 * @param timeBucketMinutes time bucket truncation granularity in minutes (default 1)
 */
public record PlatformMetricsConfig(
        RedisPluginConfig redisConfig,
        DataSource dataSource,
        int timeBucketMinutes,
        JedisPooled sharedRedisClient) {
    public PlatformMetricsConfig {
        if (timeBucketMinutes <= 0) {
            throw new IllegalArgumentException(
                    "timeBucketMinutes must be > 0, got " + timeBucketMinutes);
        }
    }

    public PlatformMetricsConfig(RedisPluginConfig redisConfig, DataSource dataSource) {
        this(redisConfig, dataSource, 1, null);
    }

    public PlatformMetricsConfig(
            RedisPluginConfig redisConfig, DataSource dataSource, JedisPooled sharedRedisClient) {
        this(redisConfig, dataSource, 1, sharedRedisClient);
    }
}
