package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.core.models.TenantInfo;
import com.koostack.operio.core.query.RetryableQuery;
import com.koostack.operio.core.query.RetryableQuery.RetryConfig;
import java.util.List;
import org.jooq.DSLContext;

/**
 * Query actuator that lists all tenants.
 */
public class ListTenantsQueryActuator
        implements QueryActuator<ListTenantsQueryActuator.ListTenantsQuery, List<TenantInfo>> {

    private final DSLContext dsl;
    private final RetryConfig retryConfig;
    private final CoreRecordMapper mapper;

    public ListTenantsQueryActuator(DSLContext dsl, RetryConfig retryConfig, CoreRecordMapper mapper) {
        this.dsl = dsl;
        this.retryConfig = retryConfig;
        this.mapper = mapper;
    }

    /** Query input for listing all tenants. */
    public record ListTenantsQuery() {}

    @Override
    public List<TenantInfo> execute(ListTenantsQuery query) {
        return RetryableQuery.withRetryCollection(
                () ->
                        dsl.select()
                                .from(CoreRecordMapper.TENANT)
                                .orderBy(CoreRecordMapper.CREATED_AT.asc())
                                .fetch()
                                .map(mapper::toTenantInfo),
                retryConfig,
                "listTenants");
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
