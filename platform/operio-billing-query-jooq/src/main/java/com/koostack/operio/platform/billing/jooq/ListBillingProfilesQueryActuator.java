package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.billing.models.BillingProfileInfo;
import com.koostack.operio.core.query.RetryableQuery;
import com.koostack.operio.core.query.RetryableQuery.RetryConfig;
import java.util.List;
import org.jooq.DSLContext;

/**
 * Query actuator that lists all billing profiles.
 */
public class ListBillingProfilesQueryActuator
        implements QueryActuator<ListBillingProfilesQueryActuator.ListBillingProfilesQuery, List<BillingProfileInfo>> {

    private final DSLContext dsl;
    private final RetryConfig retryConfig;

    public ListBillingProfilesQueryActuator(DSLContext dsl, RetryConfig retryConfig) {
        this.dsl = dsl;
        this.retryConfig = retryConfig;
    }

    /** Query input for listing all billing profiles. */
    public record ListBillingProfilesQuery() {}

    @Override
    public List<BillingProfileInfo> execute(ListBillingProfilesQuery query) {
        return RetryableQuery.withRetryCollection(
                () ->
                        dsl.select()
                                .from(BillingRecordMapper.BILLING_PROFILE)
                                .orderBy(BillingRecordMapper.BP_CREATED_AT.asc())
                                .fetch()
                                .map(BillingRecordMapper::toProfileInfo),
                retryConfig,
                "listBillingProfiles");
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
