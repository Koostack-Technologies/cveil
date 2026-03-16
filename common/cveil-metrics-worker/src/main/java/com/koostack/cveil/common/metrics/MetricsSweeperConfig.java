package com.koostack.cveil.common.metrics;

import com.koostack.cveil.plugin.redis.common.RedisPluginConfig;
import javax.sql.DataSource;
import redis.clients.jedis.JedisPooled;

/**
 * Configuration for the metrics sweeper.
 *
 * @param dataSource the database data source for telemetry cleanup
 * @param redisConfig Redis connection config for stream trimming, or {@code null}
 * @param retentionDays number of days to retain telemetry data (default 30)
 * @param platformSchemaName the platform metrics schema name (default "cveilmetrics")
 * @param sharedRedisClient optional pre-built Redis client to reuse (avoids creating a new pool)
 */
public record MetricsSweeperConfig(
        DataSource dataSource,
        RedisPluginConfig redisConfig,
        int retentionDays,
        String platformSchemaName,
        JedisPooled sharedRedisClient) {
    public MetricsSweeperConfig(DataSource dataSource, RedisPluginConfig redisConfig) {
        this(dataSource, redisConfig, 30, "cveilmetrics", null);
    }

    public MetricsSweeperConfig(
            DataSource dataSource, RedisPluginConfig redisConfig, JedisPooled sharedRedisClient) {
        this(dataSource, redisConfig, 30, "cveilmetrics", sharedRedisClient);
    }
}
