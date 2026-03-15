package com.koostack.cveil.common.metrics;

import com.koostack.cveil.plugin.redis.common.RedisPluginConfig;
import javax.sql.DataSource;

/**
 * Configuration for the metrics sweeper.
 *
 * @param dataSource the database data source for telemetry cleanup
 * @param redisConfig Redis connection config for stream trimming, or {@code null}
 * @param retentionDays number of days to retain telemetry data (default 30)
 * @param platformSchemaName the platform metrics schema name (default "platform_metrics")
 */
public record MetricsSweeperConfig(
        DataSource dataSource,
        RedisPluginConfig redisConfig,
        int retentionDays,
        String platformSchemaName) {
    public MetricsSweeperConfig(DataSource dataSource, RedisPluginConfig redisConfig) {
        this(dataSource, redisConfig, 30, "platform_metrics");
    }
}
