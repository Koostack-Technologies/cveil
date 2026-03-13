package com.koostack.operio.core.metrics;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricTest {

    @Test
    void builderCreatesMetricWithDefaults() {
        Metric metric = Metric.builder().metricName("api_requests").metricValue(42.0).build();

        assertEquals("api_requests", metric.getMetricName());
        assertEquals(42.0, metric.getMetricValue());
        assertEquals(MetricType.COUNTER, metric.getMetricType());
        assertNull(metric.getTenantId());
        assertTrue(metric.getDimensions().isEmpty());
        assertNotNull(metric.getPublishedAt());
        assertTrue(metric.isPlatformScoped());
    }

    @Test
    void builderCreatesMetricWithAllFields() {
        Instant now = Instant.now();
        Metric metric =
                Metric.builder()
                        .metricName("proxy_latency_ms")
                        .metricValue(123.45)
                        .metricType(MetricType.GAUGE)
                        .tenantId("acme")
                        .dimensions(Map.of("region", "us-east", "status", "200"))
                        .publishedAt(now)
                        .build();

        assertEquals("proxy_latency_ms", metric.getMetricName());
        assertEquals(123.45, metric.getMetricValue());
        assertEquals(MetricType.GAUGE, metric.getMetricType());
        assertEquals("acme", metric.getTenantId());
        assertEquals(Map.of("region", "us-east", "status", "200"), metric.getDimensions());
        assertEquals(now, metric.getPublishedAt());
        assertFalse(metric.isPlatformScoped());
    }

    @Test
    void builderRequiresMetricName() {
        assertThrows(NullPointerException.class, () -> Metric.builder().metricValue(1.0).build());
    }

    @Test
    void dimensionsAreImmutable() {
        Map<String, String> dims = new java.util.HashMap<>();
        dims.put("key", "value");
        Metric metric = Metric.builder().metricName("test").dimensions(dims).build();

        assertThrows(
                UnsupportedOperationException.class,
                () -> metric.getDimensions().put("new", "entry"));
    }

    @Test
    void platformScopedWhenNoTenantId() {
        Metric m1 = Metric.builder().metricName("test").build();
        assertTrue(m1.isPlatformScoped());

        Metric m2 = Metric.builder().metricName("test").tenantId("t1").build();
        assertFalse(m2.isPlatformScoped());
    }
}
