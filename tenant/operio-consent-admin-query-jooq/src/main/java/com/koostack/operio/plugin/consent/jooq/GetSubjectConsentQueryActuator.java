package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.SubjectConsentView;
import java.util.List;

/** Query actuator that retrieves the consent state for a single subject. */
public class GetSubjectConsentQueryActuator
        implements QueryActuator<
                GetSubjectConsentQueryActuator.GetSubjectConsentQuery, SubjectConsentView> {

    private final ConsentLedgerRepository consentLedgerRepository;
    private final ConsentAdminRecordMapper mapper;

    public GetSubjectConsentQueryActuator(
            ConsentLedgerRepository consentLedgerRepository, ConsentAdminRecordMapper mapper) {
        this.consentLedgerRepository = consentLedgerRepository;
        this.mapper = mapper;
    }

    /**
     * Query input for retrieving a subject's consent state.
     *
     * @param subjectId the data subject identifier
     */
    public record GetSubjectConsentQuery(String subjectId) {}

    @Override
    public SubjectConsentView execute(GetSubjectConsentQuery query) {
        var records = consentLedgerRepository.findBySubject(query.subjectId());

        List<SubjectConsentView.ConsentLedgerEntryView> entries =
                records.stream().map(r -> mapper.toLedgerEntryView(r, query.subjectId())).toList();

        return new SubjectConsentView(query.subjectId(), entries);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
