package com.koostack.operio.tenant.metrics.redis;

import com.koostack.operio.core.metrics.Metric;
import com.koostack.operio.core.metrics.MetricProjection;
import com.koostack.operio.core.metrics.MetricType;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisClientFactory;
import com.koostack.operio.plugin.redis.common.stream.AbstractStreamConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tenant metric projection — consumes from per-tenant metric streams
 * ({@code operio:metrics:tenant:{tenantId}}) and upserts into the
 * tenant-specific {@code t_{tenantId}_metrics.telemetry} table.
 *
 * <p>The projection sets the jOOQ search path to the tenant's metrics
 * schema before executing the upsert, relying on the TenantContext to
 * resolve the correct tenant ID.
 */
public class TenantRedisMetricProjection
    implements MetricProjection<TenantMetricsConfig>
{

    private static final Logger log = LoggerFactory.getLogger(TenantRedisMetricProjection.class);

    private static final String CONSUMER_GROUP = "tenant-metric-projection";

    /** Only allow alphanumeric + underscore in tenant IDs used for schema names. */
    private static final Pattern SAFE_TENANT_ID = Pattern.compile("^[a-zA-Z0-9_]+$");

    /**
     * Parameterized upsert SQL — the schema name is injected dynamically
     * based on the current tenant context.
     */
    private static final String UPSERT_TEMPLATE =
        "INSERT INTO %s.telemetry " +
        "(time_bucket, tenant_id, metric_name, dimensions, metric_value, published_at) " +
        "VALUES (?, ?, ?, ?::jsonb, ?, ?) " +
        "ON CONFLICT (time_bucket, tenant_id, metric_name, dimensions) " +
        "DO UPDATE SET metric_value = %s.telemetry.metric_value + EXCLUDED.metric_value, " +
        "              published_at = EXCLUDED.published_at";

    private static final String GAUGE_UPSERT_TEMPLATE =
        "INSERT INTO %s.telemetry " +
        "(time_bucket, tenant_id, metric_name, dimensions, metric_value, published_at) " +
        "VALUES (?, ?, ?, ?::jsonb, ?, ?) " +
        "ON CONFLICT (time_bucket, tenant_id, metric_name, dimensions) " +
        "DO UPDATE SET metric_value = EXCLUDED.metric_value, " +
        "              published_at = EXCLUDED.published_at";

    private volatile DataSource dataSource;
    private volatile TenantMetricStreamConsumer consumer;
    private volatile int timeBucketMinutes;

    @Override
    public String getName() {
        return "tenant-redis-metric-projection";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<TenantMetricsConfig> getConfigurationType() {
        return TenantMetricsConfig.class;
    }

    @Override
    public void configure(TenantMetricsConfig config) {
        this.dataSource = config.dataSource();
        this.timeBucketMinutes = config.timeBucketMinutes();
        JedisProvider jedisProvider = JedisProvider.shared(
            RedisClientFactory.createClient(
                config.redisConfig().host(),
                config.redisConfig().port(),
                config.redisConfig().password()
            )
        );
        this.consumer = new TenantMetricStreamConsumer(jedisProvider, 5_000, 100);
        this.consumer.registerHandler(CONSUMER_GROUP,
            (streamKey, messageId, fields) -> projectMetric(fields));
        log.info("TenantRedisMetricProjection configured");
    }

    @Override
    public boolean isReady() {
        return dataSource != null && consumer != null;
    }

    @Override
    public void start() {
        log.info("Tenant metric projection started (tenants added dynamically)");
    }

    @Override
    public void stop() {
        if (consumer != null) {
            consumer.close();
        }
        log.info("Tenant metric projection stopped");
    }

    /**
     * Adds a tenant to the metric stream consumer.
     * Called during tenant provisioning.
     *
     * @param tenantId the tenant identifier
     */
    public void addTenant(String tenantId) {
        if (consumer != null) {
            consumer.addTenant(tenantId);
        }
    }

    /**
     * Removes a tenant from the metric stream consumer.
     * Called during tenant teardown.
     *
     * @param tenantId the tenant identifier
     */
    public void removeTenant(String tenantId) {
        if (consumer != null) {
            consumer.removeTenant(tenantId);
        }
    }

    /**
     * Returns the underlying stream consumer for lifecycle orchestration.
     */
    public TenantMetricStreamConsumer getStreamConsumer() {
        return consumer;
    }

    private void projectMetric(Map<String, String> fields) {
        String metricName = fields.get(Metric.FIELD_METRIC_NAME);
        String metricValueStr = fields.get(Metric.FIELD_METRIC_VALUE);
        String metricTypeStr = fields.get(Metric.FIELD_METRIC_TYPE);
        String tenantId = fields.get(Metric.FIELD_TENANT_ID);
        String dimensions = fields.getOrDefault(Metric.FIELD_DIMENSIONS, "{}");
        String publishedAtStr = fields.get(Metric.FIELD_PUBLISHED_AT);

        if (metricName == null || metricValueStr == null || tenantId == null) {
            log.warn("Skipping metric with missing name, value, or tenantId: {}", fields);
            return;
        }

        if (!SAFE_TENANT_ID.matcher(tenantId).matches()) {
            log.warn("Skipping metric with invalid tenantId '{}' — must be alphanumeric/underscore only", tenantId);
            return;
        }

        double metricValue;
        try {
            metricValue = Double.parseDouble(metricValueStr);
        } catch (NumberFormatException e) {
            log.warn("Skipping metric '{}' with unparseable value '{}': {}", metricName, metricValueStr, e.getMessage());
            return;
        }

        MetricType metricType;
        try {
            metricType = metricTypeStr != null ? MetricType.valueOf(metricTypeStr) : MetricType.COUNTER;
        } catch (IllegalArgumentException e) {
            log.warn("Skipping metric '{}' with unknown type '{}': {}", metricName, metricTypeStr, e.getMessage());
            return;
        }

        Instant publishedAt;
        try {
            publishedAt = publishedAtStr != null ? Instant.parse(publishedAtStr) : Instant.now();
        } catch (Exception e) {
            log.warn("Skipping metric '{}' with unparseable publishedAt '{}': {}", metricName, publishedAtStr, e.getMessage());
            return;
        }
        Instant timeBucket = publishedAt.truncatedTo(ChronoUnit.MINUTES)
            .minus(publishedAt.atZone(ZoneOffset.UTC).getMinute() % timeBucketMinutes,
                   ChronoUnit.MINUTES);

        // Resolve the tenant's metrics schema
        String metricsSchema = "t_" + tenantId + "_metrics";
        String template = metricType == MetricType.GAUGE ? GAUGE_UPSERT_TEMPLATE : UPSERT_TEMPLATE;
        String sql = String.format(template, metricsSchema, metricsSchema);

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
            log.error("Failed to project metric '{}' into {}.telemetry for tenant '{}'",
                metricName, metricsSchema, tenantId, e);
        }
    }

    /**
     * Per-tenant metric stream consumer — routes to
     * {@code operio:metrics:tenant:{tenantId}}.
     */
    public static class TenantMetricStreamConsumer extends AbstractStreamConsumer {

        public TenantMetricStreamConsumer(JedisProvider jedisProvider, long blockMs, int batchSize) {
            super(jedisProvider, blockMs, batchSize);
        }

        @Override
        protected String streamKeyForTenant(String tenantId) {
            return TenantRedisMetricPublisher.streamKeyForTenant(tenantId);
        }
    }
}
