package com.koostack.cveil.core.cqrs;

import com.koostack.cveil.core.metrics.Metric;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.metrics.MetricType;
import com.koostack.cveil.core.models.TenantContext;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decorator that wraps any {@link CommandDispatcher} or {@link QueryDispatcher} and automatically
 * publishes a COUNTER metric for every successful dispatch.
 *
 * <p>Metric name: {@code cqrs_dispatch} with dimensions {@code {domain, operation}}. The {@code
 * operation} dimension is derived from the command/query class simple name (e.g. {@code
 * CreateTenantCommand}).
 *
 * <p>Tenant ID is resolved at dispatch time from {@link TenantContext} (ScopedValue). If no tenant
 * context is bound (platform-level commands), the fallback tenant ID from construction is used.
 *
 * <p>This eliminates the need to inject {@link MetricPublisherService} into every individual
 * actuator — metrics are captured centrally at the dispatch boundary.
 */
public class InstrumentedDispatcher implements CommandDispatcher, QueryDispatcher {

    private static final Logger LOG = Logger.getLogger(InstrumentedDispatcher.class.getName());

    private final CommandDispatcher commandDelegate;
    private final QueryDispatcher queryDelegate;
    private final MetricPublisherService<?> metrics;
    private final String domain;
    private final String fallbackTenantId;

    /**
     * Wraps a command dispatcher.
     *
     * @param delegate the real command dispatcher
     * @param metrics the metrics publisher (may be null — metrics are best-effort)
     * @param domain the domain label (e.g. "iam", "core", "billing", "consent")
     * @param fallbackTenantId tenant ID used when TenantContext is not bound (e.g. "SYSTEM")
     */
    public InstrumentedDispatcher(
            CommandDispatcher delegate,
            MetricPublisherService<?> metrics,
            String domain,
            String fallbackTenantId) {
        this.commandDelegate = delegate;
        this.queryDelegate = delegate instanceof QueryDispatcher qd ? qd : null;
        this.metrics = metrics;
        this.domain = domain;
        this.fallbackTenantId = fallbackTenantId;
    }

    /**
     * Wraps a query dispatcher.
     *
     * @param delegate the real query dispatcher
     * @param metrics the metrics publisher (may be null — metrics are best-effort)
     * @param domain the domain label (e.g. "iam", "core", "billing", "consent")
     * @param fallbackTenantId tenant ID used when TenantContext is not bound (e.g. "SYSTEM")
     */
    public InstrumentedDispatcher(
            QueryDispatcher delegate,
            MetricPublisherService<?> metrics,
            String domain,
            String fallbackTenantId) {
        this.queryDelegate = delegate;
        this.commandDelegate = delegate instanceof CommandDispatcher cd ? cd : null;
        this.metrics = metrics;
        this.domain = domain;
        this.fallbackTenantId = fallbackTenantId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <I, O> O dispatch(I input) {
        // Determine which delegate to use based on what's available
        Object delegate = commandDelegate != null ? commandDelegate : queryDelegate;
        if (delegate == null) {
            throw new IllegalStateException("No delegate dispatcher configured");
        }

        // Route to the correct delegate
        O result;
        if (delegate instanceof CommandDispatcher cd) {
            result = cd.dispatch(input);
        } else {
            result = ((QueryDispatcher) delegate).dispatch(input);
        }

        // Publish metric after successful dispatch (best-effort)
        publishMetric(input);
        return result;
    }

    private <I> void publishMetric(I input) {
        if (metrics == null) return;
        try {
            String tenantId = TenantContext.get();
            if (tenantId == null) {
                tenantId = fallbackTenantId;
            }

            String operation = input.getClass().getSimpleName();
            metrics.publish(
                    Metric.builder()
                            .metricName("cqrs_dispatch")
                            .metricType(MetricType.COUNTER)
                            .metricValue(1.0)
                            .tenantId(tenantId)
                            .dimensions(Map.of("domain", domain, "operation", operation))
                            .build());
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to publish dispatch metric: {0}", e.getMessage());
        }
    }
}
