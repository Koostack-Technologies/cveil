package com.koostack.operio.tenant.metrics.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.metrics.Metric;
import com.koostack.operio.core.metrics.MetricProjection;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisClientFactory;
import com.koostack.operio.plugin.redis.common.stream.AbstractStreamConsumer;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

/**
 * Rate-limit metric projection — consumes from per-tenant metric streams ({@code
 * operio:metrics:tenant:{tenantId}}) and tail-updates Redis rate-limit counters used by {@code
 * RateLimitService} for enforcement.
 *
 * <p>This projection only processes rate-limit-relevant metrics:
 *
 * <ul>
 *   <li>{@code consent_evaluations} — incremented via Redis {@code INCR} on the monthly evaluation
 *       counter key
 *   <li>{@code consent_subjects} — added via Redis {@code PFADD} on the monthly subject HyperLogLog
 *       key
 * </ul>
 *
 * <p>All other metrics are silently ignored (they are handled by {@link
 * TenantRedisMetricProjection} which writes to Postgres).
 *
 * <p>This projection writes exclusively to Redis — it does not touch Postgres. The Postgres
 * telemetry table is maintained by the existing {@link TenantRedisMetricProjection} consuming the
 * same stream via a different consumer group.
 *
 * <h2>Redis Key Patterns</h2>
 *
 * <table>
 *   <tr><th>Metric</th><th>Key Pattern</th><th>Operation</th><th>TTL</th></tr>
 *   <tr><td>{@code consent_evaluations}</td>
 *       <td>{@code ratelimit:{tenantId}:eval:{YYYY-MM}}</td>
 *       <td>INCR</td><td>35 days</td></tr>
 *   <tr><td>{@code consent_subjects}</td>
 *       <td>{@code ratelimit:{tenantId}:subjects:{YYYY-MM}}</td>
 *       <td>PFADD</td><td>35 days</td></tr>
 * </table>
 *
 * @see TenantRedisMetricProjection
 */
public class RateLimitRedisMetricProjection implements MetricProjection<TenantMetricsConfig> {

    private static final Logger log = LoggerFactory.getLogger(RateLimitRedisMetricProjection.class);

    private static final String CONSUMER_GROUP = "ratelimit-metric-projection";

    static final String METRIC_CONSENT_EVALUATIONS = "consent_evaluations";
    static final String METRIC_CONSENT_SUBJECTS = "consent_subjects";
    private static final Set<String> RELEVANT_METRICS =
            Set.of(METRIC_CONSENT_EVALUATIONS, METRIC_CONSENT_SUBJECTS);

    /** Dimension key for the subject ID in consent_subjects metrics. */
    static final String DIM_SUBJECT_ID = "subjectId";

    private static final String KEY_PREFIX = "ratelimit:";
    private static final String EVAL_INFIX = ":eval:";
    private static final String SUBJECT_INFIX = ":subjects:";

    /** 35 days — covers the longest month + grace for boundary queries. */
    private static final long MONTHLY_TTL_SECONDS = Duration.ofDays(35).toSeconds();

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Lua script that atomically increments a key and sets its TTL on first creation. Prevents
     * orphaned keys without TTL.
     */
    private static final String LUA_INCR_EXPIRE =
            "local curr = redis.call('INCR', KEYS[1]) "
                    + "if curr == 1 then "
                    + "  redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "end "
                    + "return curr";

    /**
     * Lua script that atomically adds to a HyperLogLog and sets TTL on first creation. Mirrors the
     * INCR+EXPIRE script — without this, a crash between PFADD and EXPIRE could leave a key without
     * TTL.
     *
     * <p>KEYS[1] = the HLL key<br>
     * ARGV[1] = TTL in seconds<br>
     * ARGV[2] = the element to add
     */
    private static final String LUA_PFADD_EXPIRE =
            "local added = redis.call('PFADD', KEYS[1], ARGV[2]) "
                    + "if redis.call('TTL', KEYS[1]) == -1 then "
                    + "  redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "end "
                    + "return added";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE_REF = new TypeReference<>() {};

    private volatile JedisPooled jedis;
    private volatile RateLimitStreamConsumer consumer;

    @Override
    public String getName() {
        return "ratelimit-redis-metric-projection";
    }

    @Override
    public int getPriority() {
        return 90;
    }

    @Override
    public Class<TenantMetricsConfig> getConfigurationType() {
        return TenantMetricsConfig.class;
    }

    @Override
    public void configure(TenantMetricsConfig config) {
        this.jedis =
                RedisClientFactory.createClient(
                        config.redisConfig().host(),
                        config.redisConfig().port(),
                        config.redisConfig().password());
        JedisProvider jedisProvider = JedisProvider.shared(this.jedis);
        this.consumer = new RateLimitStreamConsumer(jedisProvider, 5_000, 100);
        this.consumer.registerHandler(
                CONSUMER_GROUP, (streamKey, messageId, fields) -> projectMetric(fields));
        log.info("RateLimitRedisMetricProjection configured");
    }

    @Override
    public boolean isReady() {
        return jedis != null && consumer != null;
    }

    @Override
    public void start() {
        log.info("Rate-limit metric projection started (tenants added dynamically)");
    }

    @Override
    public void stop() {
        if (consumer != null) {
            consumer.close();
        }
        if (jedis != null) {
            try {
                jedis.close();
            } catch (Exception e) {
                log.warn("Error closing Redis client", e);
            }
        }
        log.info("Rate-limit metric projection stopped");
    }

    /** Adds a tenant to the metric stream consumer. Called during tenant provisioning. */
    public void addTenant(String tenantId) {
        if (consumer != null) {
            consumer.addTenant(tenantId);
        }
    }

    /** Removes a tenant from the metric stream consumer. Called during tenant teardown. */
    public void removeTenant(String tenantId) {
        if (consumer != null) {
            consumer.removeTenant(tenantId);
        }
    }

    /** Returns the underlying stream consumer for lifecycle orchestration. */
    public RateLimitStreamConsumer getStreamConsumer() {
        return consumer;
    }

    private void projectMetric(Map<String, String> fields) {
        String metricName = fields.get(Metric.FIELD_METRIC_NAME);
        if (metricName == null || !RELEVANT_METRICS.contains(metricName)) {
            return;
        }

        String tenantId = fields.get(Metric.FIELD_TENANT_ID);
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("Skipping rate-limit metric with missing tenantId: {}", fields);
            return;
        }

        String monthKey = YearMonth.now(ZoneOffset.UTC).format(MONTH_FORMATTER);

        try {
            if (METRIC_CONSENT_EVALUATIONS.equals(metricName)) {
                String redisKey = KEY_PREFIX + tenantId + EVAL_INFIX + monthKey;
                jedis.eval(
                        LUA_INCR_EXPIRE,
                        java.util.List.of(redisKey),
                        java.util.List.of(String.valueOf(MONTHLY_TTL_SECONDS)));
            } else if (METRIC_CONSENT_SUBJECTS.equals(metricName)) {
                String dimensions = fields.getOrDefault(Metric.FIELD_DIMENSIONS, "{}");
                String subjectId = extractSubjectId(dimensions);
                if (subjectId == null) {
                    log.warn("Skipping consent_subjects metric with missing subjectId dimension");
                    return;
                }
                String hllKey = KEY_PREFIX + tenantId + SUBJECT_INFIX + monthKey;
                // Atomic PFADD + conditional EXPIRE via Lua — mirrors the
                // INCR+EXPIRE script to prevent orphaned keys without TTL.
                jedis.eval(
                        LUA_PFADD_EXPIRE,
                        java.util.List.of(hllKey),
                        java.util.List.of(String.valueOf(MONTHLY_TTL_SECONDS), subjectId));
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to project rate-limit metric '{}' for tenant '{}': {}",
                    metricName,
                    tenantId,
                    e.getMessage());
        }
    }

    /**
     * Extracts the subjectId from the JSON dimensions string. Uses Jackson for correct handling of
     * escaped characters.
     */
    static String extractSubjectId(String dimensionsJson) {
        if (dimensionsJson == null || dimensionsJson.equals("{}")) {
            return null;
        }
        try {
            Map<String, String> dims = OBJECT_MAPPER.readValue(dimensionsJson, MAP_TYPE_REF);
            return dims.get(DIM_SUBJECT_ID);
        } catch (Exception e) {
            log.warn("Failed to parse dimensions JSON: {}", dimensionsJson, e);
            return null;
        }
    }

    /**
     * Per-tenant rate-limit stream consumer — routes to {@code operio:metrics:tenant:{tenantId}}.
     */
    public static class RateLimitStreamConsumer extends AbstractStreamConsumer {

        public RateLimitStreamConsumer(JedisProvider jedisProvider, long blockMs, int batchSize) {
            super(jedisProvider, blockMs, batchSize);
        }

        @Override
        protected String streamKeyForTenant(String tenantId) {
            return TenantRedisMetricPublisher.streamKeyForTenant(tenantId);
        }
    }
}
