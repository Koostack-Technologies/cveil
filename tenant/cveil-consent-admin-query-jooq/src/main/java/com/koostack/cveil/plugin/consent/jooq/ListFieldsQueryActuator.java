package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.FieldView;
import java.util.List;

/** Query actuator that lists all registered fields with entity and category info. */
public class ListFieldsQueryActuator
        implements QueryActuator<ListFieldsQueryActuator.ListFieldsQuery, List<FieldView>> {

    private final FieldRepository fieldRepository;
    private final ConsentAdminRecordMapper mapper;

    public ListFieldsQueryActuator(
            FieldRepository fieldRepository, ConsentAdminRecordMapper mapper) {
        this.fieldRepository = fieldRepository;
        this.mapper = mapper;
    }

    /** Query input for listing all fields. */
    public record ListFieldsQuery() {}

    @Override
    public List<FieldView> execute(ListFieldsQuery query) {
        return fieldRepository.findAll().stream().map(mapper::toFieldView).toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
