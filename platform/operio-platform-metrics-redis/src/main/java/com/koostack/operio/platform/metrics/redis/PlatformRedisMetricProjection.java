package com.koostack.operio.platform.metrics.redis;

import com.koostack.operio.core.metrics.Metric;
import com.koostack.operio.core.metrics.MetricProjection;
import com.koostack.operio.core.metrics.MetricType;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisClientFactory;
import com.koostack.operio.plugin.redis.common.stream.AbstractStreamConsumer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform metric projection — consumes from the {@code operio:metrics:platform} Redis stream and
 * upserts into the {@code platform_metrics.telemetry} table.
 */
public class PlatformRedisMetricProjection implements MetricProjection<PlatformMetricsConfig> {

    private static final Logger log = LoggerFactory.getLogger(PlatformRedisMetricProjection.class);

    private static final String CONSUMER_GROUP = "platform-metric-projection";

    private static final String UPSERT_SQL =
            "INSERT INTO platform_metrics.telemetry (time_bucket, tenant_id, metric_name,"
                + " dimensions, metric_value, published_at) VALUES (?, ?, ?, ?::jsonb, ?, ?) ON"
                + " CONFLICT (time_bucket, tenant_id, metric_name, dimensions) DO UPDATE SET"
                + " metric_value = platform_metrics.telemetry.metric_value + EXCLUDED.metric_value,"
                + "               published_at = EXCLUDED.published_at";

    private static final String GAUGE_UPSERT_SQL =
            "INSERT INTO platform_metrics.telemetry (time_bucket, tenant_id, metric_name,"
                    + " dimensions, metric_value, published_at) VALUES (?, ?, ?, ?::jsonb, ?, ?) ON"
                    + " CONFLICT (time_bucket, tenant_id, metric_name, dimensions) DO UPDATE SET"
                    + " metric_value = EXCLUDED.metric_value,               published_at ="
                    + " EXCLUDED.published_at";

    private volatile DataSource dataSource;
    private volatile PlatformStreamConsumer consumer;
    private volatile int timeBucketMinutes;

    @Override
    public String getName() {
        return "platform-redis-metric-projection";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<PlatformMetricsConfig> getConfigurationType() {
        return PlatformMetricsConfig.class;
    }

    @Override
    public void configure(PlatformMetricsConfig config) {
        this.dataSource = config.dataSource();
        this.timeBucketMinutes = config.timeBucketMinutes();
        JedisProvider jedisProvider =
                JedisProvider.shared(
                        RedisClientFactory.createClient(
                                config.redisConfig().host(),
                                config.redisConfig().port(),
                                config.redisConfig().password()));
        this.consumer = new PlatformStreamConsumer(jedisProvider, 5_000, 100);
        log.info("PlatformRedisMetricProjection configured");
    }

    @Override
    public boolean isReady() {
        return dataSource != null && consumer != null;
    }

    @Override
    public void start() {
        consumer.registerHandler(
                CONSUMER_GROUP, (streamKey, messageId, fields) -> projectMetric(fields));
        // Use "PLATFORM" as the single "tenant" for the platform stream
        consumer.addTenant("PLATFORM");
        log.info("Platform metric projection started");
    }

    @Override
    public void stop() {
        if (consumer != null) {
            consumer.close();
        }
        log.info("Platform metric projection stopped");
    }

    private void projectMetric(java.util.Map<String, String> fields) {
        String metricName = fields.get(Metric.FIELD_METRIC_NAME);
        String metricValueStr = fields.get(Metric.FIELD_METRIC_VALUE);
        String metricTypeStr = fields.get(Metric.FIELD_METRIC_TYPE);
        String tenantId = fields.getOrDefault(Metric.FIELD_TENANT_ID, "SYSTEM");
        String dimensions = fields.getOrDefault(Metric.FIELD_DIMENSIONS, "{}");
        String publishedAtStr = fields.get(Metric.FIELD_PUBLISHED_AT);

        if (metricName == null || metricValueStr == null) {
            log.warn("Skipping metric with missing name or value: {}", fields);
            return;
        }

        double metricValue;
        try {
            metricValue = Double.parseDouble(metricValueStr);
        } catch (NumberFormatException e) {
            log.warn(
                    "Skipping metric '{}' with unparseable value '{}': {}",
                    metricName,
                    metricValueStr,
                    e.getMessage());
            return;
        }

        MetricType metricType;
        try {
            metricType =
                    metricTypeStr != null ? MetricType.valueOf(metricTypeStr) : MetricType.COUNTER;
        } catch (IllegalArgumentException e) {
            log.warn(
                    "Skipping metric '{}' with unknown type '{}': {}",
                    metricName,
                    metricTypeStr,
                    e.getMessage());
            return;
        }

        Instant publishedAt;
        try {
            publishedAt = publishedAtStr != null ? Instant.parse(publishedAtStr) : Instant.now();
        } catch (Exception e) {
            log.warn(
                    "Skipping metric '{}' with unparseable publishedAt '{}': {}",
                    metricName,
                    publishedAtStr,
                    e.getMessage());
            return;
        }
        Instant timeBucket =
                publishedAt
                        .truncatedTo(ChronoUnit.MINUTES)
                        .minus(
                                publishedAt.atZone(java.time.ZoneOffset.UTC).getMinute()
                                        % timeBucketMinutes,
                                ChronoUnit.MINUTES);

        String sql = metricType == MetricType.GAUGE ? GAUGE_UPSERT_SQL : UPSERT_SQL;

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(timeBucket));
            ps.setString(2, tenantId);
            ps.setString(3, metricName);
            ps.setString(4, dimensions);
            ps.setDouble(5, metricValue);
            ps.setTimestamp(6, Timestamp.from(publishedAt));
            ps.executeUpdate();
        } catch (Exception e) {
            log.error(
                    "Failed to project metric '{}' into platform_metrics.telemetry", metricName, e);
        }
    }

    /**
     * Single-stream consumer for the platform metric stream. Uses "PLATFORM" as the tenant
     * identifier since there is only one stream.
     */
    private static class PlatformStreamConsumer extends AbstractStreamConsumer {

        PlatformStreamConsumer(JedisProvider jedisProvider, long blockMs, int batchSize) {
            super(jedisProvider, blockMs, batchSize);
        }

        @Override
        protected String streamKeyForTenant(String tenantId) {
            return PlatformRedisMetricPublisher.STREAM_KEY;
        }
    }
}
