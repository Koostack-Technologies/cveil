package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.EntityView;
import java.util.Optional;

/** Query actuator that retrieves a single entity by name. */
public class GetEntityQueryActuator
        implements QueryActuator<GetEntityQueryActuator.GetEntityQuery, Optional<EntityView>> {

    private final EntityRepository entityRepository;

    public GetEntityQueryActuator(EntityRepository entityRepository) {
        this.entityRepository = entityRepository;
    }

    /**
     * Query input for retrieving a single entity.
     *
     * @param entityName the entity name to look up
     */
    public record GetEntityQuery(String entityName) {}

    @Override
    public Optional<EntityView> execute(GetEntityQuery query) {
        return entityRepository
                .findByName(query.entityName())
                .map(e -> new EntityView(e.getEntityUuid(), e.getEntityName(), e.getDescription()));
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
