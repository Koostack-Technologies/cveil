package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.EntityAggregate;
import com.koostack.cveil.core.tenant.consent.events.EntityUpdatedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateEntityCommandActuator
        implements CommandActuator<UpdateEntityCommandActuator.UpdateEntityCommand, Void> {

    private final EventService<?> eventService;

    public UpdateEntityCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG = LoggerFactory.getLogger(UpdateEntityCommandActuator.class);

    public record UpdateEntityCommand(String entityName, String description) {}

    @Override
    public Void execute(UpdateEntityCommand command) {
        Objects.requireNonNull(command.entityName(), "entityName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = EntityAggregate.deriveId(tenantId, command.entityName());
        var payload = new EntityUpdatedEvent(command.entityName(), command.description());
        AggregateKey key = new AggregateKey(aggregateId, EntityAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.ENTITY_UPDATED, key, payload));
        LOG.info("Entity updated: {}", command.entityName());
        return null;
    }

    @Override
    public EventService<?> eventPublisher() {
        return eventService;
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }
}
