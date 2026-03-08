package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.billing.models.BillingProfileInfo;
import com.koostack.operio.core.query.RetryableQuery;
import com.koostack.operio.core.query.RetryableQuery.RetryConfig;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Query actuator that retrieves the billing profile for a single tenant.
 */
public class GetBillingProfileQueryActuator
        implements QueryActuator<GetBillingProfileQueryActuator.GetBillingProfileQuery, BillingProfileInfo> {

    private final DSLContext dsl;
    private final RetryConfig retryConfig;

    public GetBillingProfileQueryActuator(DSLContext dsl, RetryConfig retryConfig) {
        this.dsl = dsl;
        this.retryConfig = retryConfig;
    }

    /**
     * Query input for retrieving a billing profile.
     *
     * @param tenantId the tenant business identifier
     */
    public record GetBillingProfileQuery(String tenantId) {}

    @Override
    public BillingProfileInfo execute(GetBillingProfileQuery query) {
        return RetryableQuery.withRetry(
                () -> {
                    Record record =
                            dsl.select()
                                    .from(BillingRecordMapper.BILLING_PROFILE)
                                    .where(BillingRecordMapper.BP_TENANT_ID.eq(query.tenantId()))
                                    .fetchOne();
                    return record != null ? BillingRecordMapper.toProfileInfo(record) : null;
                },
                result -> result == null,
                retryConfig,
                "getBillingProfile(" + query.tenantId() + ")");
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
