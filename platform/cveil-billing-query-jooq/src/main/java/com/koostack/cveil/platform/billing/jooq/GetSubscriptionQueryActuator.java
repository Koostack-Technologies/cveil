package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.platform.billing.models.SubscriptionInfo;
import com.koostack.cveil.core.query.RetryableQuery;
import com.koostack.cveil.core.query.RetryableQuery.RetryConfig;
import org.jooq.DSLContext;
import org.jooq.Record;

/** Query actuator that retrieves the subscription for a single tenant. */
public class GetSubscriptionQueryActuator
        implements QueryActuator<
                GetSubscriptionQueryActuator.GetSubscriptionQuery, SubscriptionInfo> {

    private final DSLContext dsl;
    private final RetryConfig retryConfig;

    public GetSubscriptionQueryActuator(DSLContext dsl, RetryConfig retryConfig) {
        this.dsl = dsl;
        this.retryConfig = retryConfig;
    }

    /**
     * Query input for retrieving a tenant subscription.
     *
     * @param tenantId the tenant business identifier
     */
    public record GetSubscriptionQuery(String tenantId) {}

    @Override
    public SubscriptionInfo execute(GetSubscriptionQuery query) {
        return RetryableQuery.withRetry(
                () -> {
                    Record record =
                            dsl.select()
                                    .from(BillingRecordMapper.SUBSCRIPTION)
                                    .where(BillingRecordMapper.SUB_TENANT_ID.eq(query.tenantId()))
                                    .fetchOne();
                    return record != null ? BillingRecordMapper.toSubscriptionInfo(record) : null;
                },
                result -> result == null,
                retryConfig,
                "getSubscription(" + query.tenantId() + ")");
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
