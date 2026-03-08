package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.platform.core.models.TenantCredentials;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Query actuator that retrieves Redis ACL credentials for a tenant.
 */
public class GetTenantCredentialsQueryActuator
        implements QueryActuator<GetTenantCredentialsQueryActuator.GetTenantCredentialsQuery, TenantCredentials> {

    private final DSLContext dsl;
    private final CoreRecordMapper mapper;

    public GetTenantCredentialsQueryActuator(DSLContext dsl, CoreRecordMapper mapper) {
        this.dsl = dsl;
        this.mapper = mapper;
    }

    /**
     * Query input for retrieving tenant Redis ACL credentials.
     *
     * @param tenantId the tenant business identifier
     */
    public record GetTenantCredentialsQuery(String tenantId) {}

    @Override
    public TenantCredentials execute(GetTenantCredentialsQuery query) {
        Record record =
                dsl.select(CoreRecordMapper.REDIS_ACL_USER, CoreRecordMapper.REDIS_ACL_PASSWORD)
                        .from(CoreRecordMapper.TENANT)
                        .where(CoreRecordMapper.TENANT_ID.eq(query.tenantId()))
                        .fetchOne();
        if (record == null) return null;
        return mapper.toTenantCredentials(record);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
