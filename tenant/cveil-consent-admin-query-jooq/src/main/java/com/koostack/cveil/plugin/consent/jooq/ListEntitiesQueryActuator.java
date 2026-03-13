package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.EntityView;
import java.util.List;

/** Query actuator that lists all registered entities. */
public class ListEntitiesQueryActuator
        implements QueryActuator<ListEntitiesQueryActuator.ListEntitiesQuery, List<EntityView>> {

    private final EntityRepository entityRepository;

    public ListEntitiesQueryActuator(EntityRepository entityRepository) {
        this.entityRepository = entityRepository;
    }

    /** Query input for listing all entities. */
    public record ListEntitiesQuery() {}

    @Override
    public List<EntityView> execute(ListEntitiesQuery query) {
        return entityRepository.findAll().stream()
                .map(e -> new EntityView(e.getEntityId(), e.getEntityName(), e.getDescription()))
                .toList();
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
