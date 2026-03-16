package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.DsarAuditLog;
import java.util.List;

/** Query actuator that retrieves the DSAR audit log entries for a single subject. */
public class GetDsarAuditLogQueryActuator
        implements QueryActuator<
                GetDsarAuditLogQueryActuator.GetDsarAuditLogQuery, List<DsarAuditLog>> {

    private final DsarAuditLogRepository dsarAuditLogRepository;

    public GetDsarAuditLogQueryActuator(DsarAuditLogRepository dsarAuditLogRepository) {
        this.dsarAuditLogRepository = dsarAuditLogRepository;
    }

    /**
     * Query input for retrieving a subject's DSAR audit log.
     *
     * @param subjectId the data subject identifier
     */
    public record GetDsarAuditLogQuery(String subjectId) {}

    @Override
    public List<DsarAuditLog> execute(GetDsarAuditLogQuery query) {
        return dsarAuditLogRepository.findBySubject(query.subjectId());
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
