package com.koostack.operio.platform.metrics.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.metrics.Metric;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisClientFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.XAddParams;

/**
 * Platform metric publisher — publishes metrics to the {@code operio:metrics:platform} Redis stream
 * with at-least-once delivery via async virtual-thread retry.
 */
public class PlatformRedisMetricPublisher
        implements MetricPublisherService<PlatformMetricsConfig>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlatformRedisMetricPublisher.class);

    public static final String STREAM_KEY = "operio:metrics:platform";
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 100;
    private static final long MAX_STREAM_LEN = 10_000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService retryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile JedisProvider jedisProvider;

    @Override
    public String getName() {
        return "platform-redis-metric-publisher";
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
        this.jedisProvider =
                JedisProvider.shared(
                        RedisClientFactory.createClient(
                                config.redisConfig().host(),
                                config.redisConfig().port(),
                                config.redisConfig().password()));
        log.info("PlatformRedisMetricPublisher configured");
    }

    @Override
    public boolean isReady() {
        return jedisProvider != null;
    }

    @Override
    public void publish(Metric metric) {
        if (jedisProvider == null) {
            throw new IllegalStateException(
                    "PlatformRedisMetricPublisher is not configured — call configure() first");
        }
        Map<String, String> fields = serializeMetric(metric);
        retryExecutor.submit(() -> publishWithRetry(fields));
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

    private void publishWithRetry(Map<String, String> fields) {
        long backoffMs = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                JedisPooled jedis = jedisProvider.get();
                var params = new XAddParams().approximateTrimming().maxLen(MAX_STREAM_LEN);
                jedis.xadd(STREAM_KEY, params, fields);
                log.debug(
                        "Published metric '{}' to stream '{}'",
                        fields.get(Metric.FIELD_METRIC_NAME),
                        STREAM_KEY);
                return;
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    log.error(
                            "Failed to publish metric after {} retries: {}",
                            MAX_RETRIES,
                            fields.get(Metric.FIELD_METRIC_NAME),
                            e);
                    return;
                }
                log.warn(
                        "Publish attempt {}/{} failed for metric '{}' — retrying after {}ms",
                        attempt,
                        MAX_RETRIES,
                        fields.get(Metric.FIELD_METRIC_NAME),
                        backoffMs,
                        e);
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
        fields.put(
                Metric.FIELD_TENANT_ID,
                metric.getTenantId() != null ? metric.getTenantId() : "SYSTEM");
        fields.put(Metric.FIELD_PUBLISHED_AT, metric.getPublishedAt().toString());
        try {
            fields.put(
                    Metric.FIELD_DIMENSIONS,
                    objectMapper.writeValueAsString(metric.getDimensions()));
        } catch (JsonProcessingException e) {
            fields.put(Metric.FIELD_DIMENSIONS, "{}");
            log.warn(
                    "Failed to serialize dimensions for metric '{}', using empty object",
                    metric.getMetricName(),
                    e);
        }
        return fields;
    }
}
