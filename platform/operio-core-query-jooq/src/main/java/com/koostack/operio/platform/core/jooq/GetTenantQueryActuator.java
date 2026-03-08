package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.core.models.TenantInfo;
import com.koostack.operio.core.query.RetryableQuery;
import com.koostack.operio.core.query.RetryableQuery.RetryConfig;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Query actuator that retrieves a single tenant by business identifier.
 */
public class GetTenantQueryActuator
        implements QueryActuator<GetTenantQueryActuator.GetTenantQuery, TenantInfo> {

    private final DSLContext dsl;
    private final RetryConfig retryConfig;
    private final CoreRecordMapper mapper;

    public GetTenantQueryActuator(DSLContext dsl, RetryConfig retryConfig, CoreRecordMapper mapper) {
        this.dsl = dsl;
        this.retryConfig = retryConfig;
        this.mapper = mapper;
    }

    /**
     * Query input for retrieving a tenant.
     *
     * @param tenantId the tenant business identifier
     */
    public record GetTenantQuery(String tenantId) {}

    @Override
    public TenantInfo execute(GetTenantQuery query) {
        return RetryableQuery.withRetry(
                () -> {
                    Record record =
                            dsl.select()
                                    .from(CoreRecordMapper.TENANT)
                                    .where(CoreRecordMapper.TENANT_ID.eq(query.tenantId()))
                                    .fetchOne();
                    return record != null ? mapper.toTenantInfo(record) : null;
                },
                result -> result == null,
                retryConfig,
                "getTenant(" + query.tenantId() + ")");
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
