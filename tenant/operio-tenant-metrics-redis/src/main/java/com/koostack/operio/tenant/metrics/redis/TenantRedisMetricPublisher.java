package com.koostack.operio.tenant.metrics.redis;

import com.koostack.operio.core.metrics.Metric;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisClientFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tenant metric publisher — publishes metrics to per-tenant Redis streams
 * ({@code operio:metrics:tenant:{tenantId}}) with at-least-once delivery
 * via async virtual-thread retry.
 */
public class TenantRedisMetricPublisher
    implements MetricPublisherService<TenantMetricsConfig>, AutoCloseable
{

    private static final Logger log = LoggerFactory.getLogger(TenantRedisMetricPublisher.class);

    /** Prefix for per-tenant metric stream keys. */
    public static final String STREAM_KEY_PREFIX = "operio:metrics:tenant:";

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 100;
    private static final long MAX_STREAM_LEN = 10_000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService retryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile JedisProvider jedisProvider;

    @Override
    public String getName() {
        return "tenant-redis-metric-publisher";
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
        this.jedisProvider = JedisProvider.shared(
            RedisClientFactory.createClient(
                config.redisConfig().host(),
                config.redisConfig().port(),
                config.redisConfig().password()
            )
        );
        log.info("TenantRedisMetricPublisher configured");
    }

    @Override
    public boolean isReady() {
        return jedisProvider != null;
    }

    @Override
    public void publish(Metric metric) {
        if (jedisProvider == null) {
            throw new IllegalStateException("TenantRedisMetricPublisher is not configured — call configure() first");
        }
        if (metric.getTenantId() == null) {
            throw new IllegalArgumentException(
                "Tenant metric publisher requires a non-null tenantId");
        }
        Map<String, String> fields = serializeMetric(metric);
        String streamKey = streamKeyForTenant(metric.getTenantId());
        retryExecutor.submit(() -> publishWithRetry(streamKey, fields));
    }

    @Override
    public void close() {
        retryExecutor.shutdown();
        try {
            if (!retryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                retryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the per-tenant metric stream key.
     *
     * @param tenantId the tenant identifier
     * @return the stream key ({@code operio:metrics:tenant:{tenantId}})
     */
    public static String streamKeyForTenant(String tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        return STREAM_KEY_PREFIX + tenantId;
    }

    private void publishWithRetry(String streamKey, Map<String, String> fields) {
        long backoffMs = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                JedisPooled jedis = jedisProvider.get();
                var params = new XAddParams()
                    .approximateTrimming()
                    .maxLen(MAX_STREAM_LEN);
                jedis.xadd(streamKey, params, fields);
                log.debug("Published metric '{}' to stream '{}'",
                    fields.get(Metric.FIELD_METRIC_NAME), streamKey);
                return;
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    log.error("Failed to publish metric after {} retries to stream '{}': {}",
                        MAX_RETRIES, streamKey, fields.get(Metric.FIELD_METRIC_NAME), e);
                    return;
                }
                log.warn("Publish attempt {}/{} failed for metric '{}' on '{}' — retrying after {}ms",
                    attempt, MAX_RETRIES, fields.get(Metric.FIELD_METRIC_NAME), streamKey, backoffMs, e);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoffMs = Math.min(backoffMs * 2, 5_000);
            }
        }
    }

    Map<String, String> serializeMetric(Metric metric) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(Metric.FIELD_METRIC_NAME, metric.getMetricName());
        fields.put(Metric.FIELD_METRIC_VALUE, String.valueOf(metric.getMetricValue()));
        fields.put(Metric.FIELD_METRIC_TYPE, metric.getMetricType().name());
        fields.put(Metric.FIELD_TENANT_ID, metric.getTenantId());
        fields.put(Metric.FIELD_PUBLISHED_AT, metric.getPublishedAt().toString());
        try {
            fields.put(Metric.FIELD_DIMENSIONS, objectMapper.writeValueAsString(metric.getDimensions()));
        } catch (JsonProcessingException e) {
            fields.put(Metric.FIELD_DIMENSIONS, "{}");
            log.warn("Failed to serialize dimensions for metric '{}', using empty object",
                metric.getMetricName(), e);
        }
        return fields;
    }
}
