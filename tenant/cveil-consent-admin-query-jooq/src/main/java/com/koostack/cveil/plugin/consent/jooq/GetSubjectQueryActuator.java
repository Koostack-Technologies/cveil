package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.SubjectView;
import java.util.Optional;

/** Query actuator that retrieves a single subject by name. */
public class GetSubjectQueryActuator
        implements QueryActuator<GetSubjectQueryActuator.GetSubjectQuery, Optional<SubjectView>> {

    private final SubjectRepository subjectRepository;
    private final ConsentAdminRecordMapper mapper;

    public GetSubjectQueryActuator(
            SubjectRepository subjectRepository, ConsentAdminRecordMapper mapper) {
        this.subjectRepository = subjectRepository;
        this.mapper = mapper;
    }

    /**
     * Query input for retrieving a single subject.
     *
     * @param subjectName the subject name to look up
     */
    public record GetSubjectQuery(String subjectName) {}

    @Override
    public Optional<SubjectView> execute(GetSubjectQuery query) {
        return subjectRepository.findByName(query.subjectName()).map(mapper::toSubjectView);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
