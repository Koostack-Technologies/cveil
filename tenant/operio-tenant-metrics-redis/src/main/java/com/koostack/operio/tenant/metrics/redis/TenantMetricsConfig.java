package com.koostack.operio.tenant.metrics.redis;

import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import javax.sql.DataSource;

/**
 * Configuration for the tenant metrics Redis implementation.
 *
 * @param redisConfig Redis connection configuration
 * @param dataSource the database data source for telemetry projection
 * @param timeBucketMinutes time bucket truncation granularity in minutes (default 1)
 */
public record TenantMetricsConfig(
        RedisPluginConfig redisConfig, DataSource dataSource, int timeBucketMinutes) {
    public TenantMetricsConfig {
        if (timeBucketMinutes <= 0) {
            throw new IllegalArgumentException(
                    "timeBucketMinutes must be > 0, got " + timeBucketMinutes);
        }
    }

    public TenantMetricsConfig(RedisPluginConfig redisConfig, DataSource dataSource) {
        this(redisConfig, dataSource, 1);
    }
}
