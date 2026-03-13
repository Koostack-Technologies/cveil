package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.SubjectView;
import java.util.List;

/** Query actuator that lists all registered subjects. */
public class ListSubjectsQueryActuator
        implements QueryActuator<ListSubjectsQueryActuator.ListSubjectsQuery, List<SubjectView>> {

    private final SubjectRepository subjectRepository;
    private final ConsentAdminRecordMapper mapper;

    public ListSubjectsQueryActuator(
            SubjectRepository subjectRepository, ConsentAdminRecordMapper mapper) {
        this.subjectRepository = subjectRepository;
        this.mapper = mapper;
    }

    /** Query input for listing all subjects. */
    public record ListSubjectsQuery() {}

    @Override
    public List<SubjectView> execute(ListSubjectsQuery query) {
        return subjectRepository.findAll().stream().map(mapper::toSubjectView).toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
