package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.core.models.TenantInfo;
import com.koostack.operio.core.query.RetryableQuery;
import com.koostack.operio.core.query.RetryableQuery.RetryConfig;
import java.util.List;
import org.jooq.DSLContext;

/** Query actuator that lists tenants filtered by status. */
public class ListTenantsByStatusQueryActuator
        implements QueryActuator<
                ListTenantsByStatusQueryActuator.ListTenantsByStatusQuery, List<TenantInfo>> {

    private final DSLContext dsl;
    private final RetryConfig retryConfig;
    private final CoreRecordMapper mapper;

    public ListTenantsByStatusQueryActuator(
            DSLContext dsl, RetryConfig retryConfig, CoreRecordMapper mapper) {
        this.dsl = dsl;
        this.retryConfig = retryConfig;
        this.mapper = mapper;
    }

    /**
     * Query input for listing tenants by status.
     *
     * @param status the status filter (e.g. "active", "suspended", "created")
     */
    public record ListTenantsByStatusQuery(String status) {}

    @Override
    public List<TenantInfo> execute(ListTenantsByStatusQuery query) {
        return RetryableQuery.withRetryCollection(
                () ->
                        dsl.select()
                                .from(CoreRecordMapper.TENANT)
                                .where(CoreRecordMapper.STATUS.eq(query.status()))
                                .orderBy(CoreRecordMapper.CREATED_AT.asc())
                                .fetch()
                                .map(mapper::toTenantInfo),
                retryConfig,
                "listTenantsByStatus(" + query.status() + ")");
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
