package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.QueryActuator;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.FieldView;

/** Query actuator that retrieves a single field by name and entity. */
public class GetFieldQueryActuator
        implements QueryActuator<GetFieldQueryActuator.GetFieldQuery, FieldView> {

    private final EntityRepository entityRepository;
    private final FieldRepository fieldRepository;
    private final ConsentAdminRecordMapper mapper;

    public GetFieldQueryActuator(
            EntityRepository entityRepository,
            FieldRepository fieldRepository,
            ConsentAdminRecordMapper mapper) {
        this.entityRepository = entityRepository;
        this.fieldRepository = fieldRepository;
        this.mapper = mapper;
    }

    /**
     * Query input for retrieving a single field.
     *
     * @param fieldName the field name
     * @param entityName the owning entity name
     */
    public record GetFieldQuery(String fieldName, String entityName) {}

    @Override
    public FieldView execute(GetFieldQuery query) {
        return entityRepository
                .findByName(query.entityName())
                .flatMap(
                        entity ->
                                fieldRepository.findByNameAndEntity(
                                        query.fieldName(), entity.getEntityId()))
                .map(mapper::toFieldView)
                .orElse(null);
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
