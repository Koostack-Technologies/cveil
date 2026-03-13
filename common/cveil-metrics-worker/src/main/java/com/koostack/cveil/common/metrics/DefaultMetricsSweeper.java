package com.koostack.cveil.common.metrics;

import com.koostack.cveil.core.metrics.MetricsSweeper;
import com.koostack.cveil.plugin.redis.common.RedisClientFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XTrimParams;

/**
 * Metrics sweeper that periodically deletes telemetry rows older than the configured retention
 * period and trims Redis metric streams.
 *
 * <p>Call {@link #sweep()} periodically (e.g. via a scheduled executor or cron) to clean up stale
 * data.
 */
public class DefaultMetricsSweeper implements MetricsSweeper<MetricsSweeperConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultMetricsSweeper.class);

    private static final String DELETE_SQL = "DELETE FROM %s.telemetry WHERE time_bucket < ?";

    private static final long TRIM_MAX_LEN = 10_000;

    /** Only allow safe characters in schema names to prevent SQL injection. */
    private static final Pattern SAFE_SCHEMA_NAME = Pattern.compile("^[a-zA-Z0-9_]+$");

    private volatile DataSource dataSource;
    private volatile JedisPooled jedis;
    private volatile int retentionDays;
    private volatile String platformSchemaName;
    private volatile Supplier<List<String>> tenantDiscovery;

    @Override
    public String getName() {
        return "redis-metrics-sweeper";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<MetricsSweeperConfig> getConfigurationType() {
        return MetricsSweeperConfig.class;
    }

    @Override
    public void configure(MetricsSweeperConfig config) {
        this.dataSource = config.dataSource();
        this.retentionDays = config.retentionDays();
        this.platformSchemaName = config.platformSchemaName();
        this.tenantDiscovery = config.tenantDiscovery();
        if (config.redisConfig() != null) {
            this.jedis =
                    RedisClientFactory.createClient(
                            config.redisConfig().host(),
                            config.redisConfig().port(),
                            config.redisConfig().password());
        }
        LOG.info("RedisMetricsSweeper configured: retentionDays={}", retentionDays);
    }

    @Override
    public boolean isReady() {
        return dataSource != null;
    }

    /**
     * Runs one sweep cycle: deletes old telemetry rows and trims Redis streams.
     *
     * @return the total number of telemetry rows deleted
     */
    public int sweep() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int totalDeleted = 0;

        // 1. Sweep platform metrics schema
        totalDeleted += sweepSchema(platformSchemaName, cutoff);

        // 2. Sweep tenant metrics schemas
        if (tenantDiscovery != null) {
            List<String> tenantIds = tenantDiscovery.get();
            for (String tenantId : tenantIds) {
                String metricsSchema = "t_" + tenantId + "_metrics";
                totalDeleted += sweepSchema(metricsSchema, cutoff);

                // Trim tenant metric Redis stream
                if (jedis != null) {
                    trimStream("cveil:metrics:tenant:" + tenantId);
                }
            }
        }

        // 3. Trim platform metric Redis stream
        if (jedis != null) {
            trimStream("cveil:metrics:platform");
        }

        LOG.info("Metrics sweep complete: deleted {} rows (cutoff={})", totalDeleted, cutoff);
        return totalDeleted;
    }

    private int sweepSchema(String schemaName, Instant cutoff) {
        if (!SAFE_SCHEMA_NAME.matcher(schemaName).matches()) {
            LOG.warn("Refusing to sweep schema with unsafe name '{}' — skipping", schemaName);
            return 0;
        }
        String sql = String.format(DELETE_SQL, schemaName);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(cutoff));
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                LOG.debug(
                        "Deleted {} rows from {}.telemetry older than {}",
                        deleted,
                        schemaName,
                        cutoff);
            }
            return deleted;
        } catch (Exception e) {
            LOG.warn("Failed to sweep schema '{}': {}", schemaName, e.getMessage());
            return 0;
        }
    }

    private void trimStream(String streamKey) {
        try {
            jedis.xtrim(streamKey, new XTrimParams().approximateTrimming().maxLen(TRIM_MAX_LEN));
            LOG.debug("Trimmed stream '{}' to ~{} entries", streamKey, TRIM_MAX_LEN);
        } catch (Exception e) {
            LOG.warn("Failed to trim stream '{}': {}", streamKey, e.getMessage());
        }
    }
}
