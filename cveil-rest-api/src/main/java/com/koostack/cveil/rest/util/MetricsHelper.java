package com.koostack.cveil.rest.util;

import com.koostack.cveil.core.metrics.Metric;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.metrics.MetricType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application-scoped helper for publishing telemetry metrics from REST resources and services. All
 * publishing is best-effort — failures are logged but never propagate to callers.
 */
@ApplicationScoped
public class MetricsHelper {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsHelper.class);

    @Inject
    @SuppressWarnings("rawtypes")
    jakarta.enterprise.inject.Instance<MetricPublisherService> publisherInstance;

    /**
     * Publishes a COUNTER metric. Best-effort — failures are silently logged.
     *
     * @param metricName the metric name (e.g. "consent_grants")
     * @param tenantId the tenant ID, or null for platform-scoped metrics
     * @param dimensions arbitrary key-value tags for grouping
     */
    @SuppressWarnings("unchecked")
    public void counter(String metricName, String tenantId, Map<String, String> dimensions) {
        publish(metricName, 1.0, MetricType.COUNTER, tenantId, dimensions);
    }

    /** Publishes a COUNTER metric with no dimensions. */
    public void counter(String metricName, String tenantId) {
        counter(metricName, tenantId, Map.of());
    }

    /** Publishes a GAUGE metric. Best-effort — failures are silently logged. */
    @SuppressWarnings("unchecked")
    public void gauge(
            String metricName, double value, String tenantId, Map<String, String> dimensions) {
        publish(metricName, value, MetricType.GAUGE, tenantId, dimensions);
    }

    @SuppressWarnings("unchecked")
    private void publish(
            String metricName,
            double value,
            MetricType type,
            String tenantId,
            Map<String, String> dimensions) {
        try {
            if (!publisherInstance.isResolvable()) {
                return;
            }
            MetricPublisherService<?> publisher = publisherInstance.get();
            if (publisher == null) {
                return;
            }
            publisher.publish(
                    Metric.builder()
                            .metricName(metricName)
                            .metricValue(value)
                            .metricType(type)
                            .tenantId(tenantId)
                            .dimensions(dimensions != null ? dimensions : Map.of())
                            .build());
        } catch (Exception e) {
            LOG.debug("Best-effort metric publish failed for '{}'", metricName, e);
        }
    }
}
