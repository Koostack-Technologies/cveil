package com.koostack.operio.common.metrics;

import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Configuration for the metrics sweeper.
 *
 * @param dataSource the database data source for telemetry cleanup
 * @param redisConfig Redis connection config for stream trimming, or {@code null}
 * @param retentionDays number of days to retain telemetry data (default 30)
 * @param platformSchemaName the platform metrics schema name (default "platform_metrics")
 * @param tenantDiscovery supplier of active tenant IDs for sweeping tenant schemas
 */
public record MetricsSweeperConfig(
        DataSource dataSource,
        RedisPluginConfig redisConfig,
        int retentionDays,
        String platformSchemaName,
        Supplier<List<String>> tenantDiscovery) {
    public MetricsSweeperConfig(
            DataSource dataSource,
            RedisPluginConfig redisConfig,
            Supplier<List<String>> tenantDiscovery) {
        this(dataSource, redisConfig, 30, "platform_metrics", tenantDiscovery);
    }
}
