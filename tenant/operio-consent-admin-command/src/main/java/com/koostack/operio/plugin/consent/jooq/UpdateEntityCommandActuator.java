package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.EntityAggregate;
import com.koostack.operio.core.tenant.consent.events.EntityUpdatedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateEntityCommandActuator
        implements Actuator<UpdateEntityCommandActuator.UpdateEntityCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateEntityCommandActuator.class);

    public record UpdateEntityCommand(String entityName, String description) {}

    @Override
    public Void execute(EventService<?> publisher, UpdateEntityCommand command) {
        Objects.requireNonNull(command.entityName(), "entityName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = EntityAggregate.deriveId(command.entityName());
        var payload = new EntityUpdatedEvent(command.entityName(), command.description());
        AggregateKey key = new AggregateKey(aggregateId, EntityAggregate.AGGREGATE_TYPE, tenantId);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.ENTITY_UPDATED, key, payload));
        LOG.info("Entity updated: {}", command.entityName());
        return null;
    }
}

