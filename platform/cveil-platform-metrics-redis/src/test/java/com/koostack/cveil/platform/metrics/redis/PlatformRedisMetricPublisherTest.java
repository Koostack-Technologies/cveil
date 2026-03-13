package com.koostack.cveil.platform.metrics.redis;

import static org.junit.jupiter.api.Assertions.*;

import com.koostack.cveil.core.metrics.Metric;
import com.koostack.cveil.core.metrics.MetricType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PlatformRedisMetricPublisher} serialization. */
class PlatformRedisMetricPublisherTest {

    @Test
    void streamKeyIsCorrect() {
        assertEquals("cveil:metrics:platform", PlatformRedisMetricPublisher.STREAM_KEY);
    }

    @Test
    void serializesMetricToStreamFields() {
        Instant now = Instant.parse("2026-02-28T10:00:00Z");
        Metric metric =
                Metric.builder()
                        .metricName("api_evaluations")
                        .metricValue(100.0)
                        .metricType(MetricType.COUNTER)
                        .tenantId("acme")
                        .dimensions(Map.of("region", "us-east"))
                        .publishedAt(now)
                        .build();

        PlatformRedisMetricPublisher publisher = new PlatformRedisMetricPublisher();
        Map<String, String> fields = publisher.serializeMetric(metric);

        assertEquals("api_evaluations", fields.get(Metric.FIELD_METRIC_NAME));
        assertEquals("100.0", fields.get(Metric.FIELD_METRIC_VALUE));
        assertEquals("COUNTER", fields.get(Metric.FIELD_METRIC_TYPE));
        assertEquals("acme", fields.get(Metric.FIELD_TENANT_ID));
        assertEquals("2026-02-28T10:00:00Z", fields.get(Metric.FIELD_PUBLISHED_AT));
        assertTrue(fields.get(Metric.FIELD_DIMENSIONS).contains("us-east"));
    }

    @Test
    void serializesPlatformMetricWithSystemTenantId() {
        Metric metric =
                Metric.builder()
                        .metricName("db_connection_count")
                        .metricValue(8.0)
                        .metricType(MetricType.GAUGE)
                        .build();

        PlatformRedisMetricPublisher publisher = new PlatformRedisMetricPublisher();
        Map<String, String> fields = publisher.serializeMetric(metric);

        assertEquals("SYSTEM", fields.get(Metric.FIELD_TENANT_ID));
        assertEquals("GAUGE", fields.get(Metric.FIELD_METRIC_TYPE));
    }

    @Test
    void serializesEmptyDimensionsAsEmptyJson() {
        Metric metric = Metric.builder().metricName("test").metricValue(1.0).build();

        PlatformRedisMetricPublisher publisher = new PlatformRedisMetricPublisher();
        Map<String, String> fields = publisher.serializeMetric(metric);

        assertEquals("{}", fields.get(Metric.FIELD_DIMENSIONS));
    }
}
