package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.DsarAuditLog;
import java.util.List;

/** Query actuator that retrieves the DSAR audit log entries for a single subject. */
public class GetDsarAuditLogQueryActuator
        implements QueryActuator<
                GetDsarAuditLogQueryActuator.GetDsarAuditLogQuery, List<DsarAuditLog>> {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final DsarAuditLogRepository dsarAuditLogRepository;

    public GetDsarAuditLogQueryActuator(DsarAuditLogRepository dsarAuditLogRepository) {
        this.dsarAuditLogRepository = dsarAuditLogRepository;
    }

    /**
     * Query input for retrieving a subject's DSAR audit log.
     *
     * @param subjectId the data subject identifier
     * @param limit max results to return (default 100, max 1000)
     * @param offset starting offset for pagination (default 0)
     */
    public record GetDsarAuditLogQuery(String subjectId, int limit, int offset) {
        public GetDsarAuditLogQuery(String subjectId) {
            this(subjectId, DEFAULT_LIMIT, 0);
        }
    }

    @Override
    public List<DsarAuditLog> execute(GetDsarAuditLogQuery query) {
        int limit = Math.max(1, Math.min(query.limit(), MAX_LIMIT));
        int offset = Math.max(0, query.offset());
        return dsarAuditLogRepository.findBySubject(query.subjectId(), limit, offset);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
