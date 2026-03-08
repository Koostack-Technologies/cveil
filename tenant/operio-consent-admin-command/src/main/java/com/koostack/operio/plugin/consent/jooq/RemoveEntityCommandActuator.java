package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.EntityAggregate;
import com.koostack.operio.core.tenant.consent.events.EntityRemovedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoveEntityCommandActuator
        implements Actuator<RemoveEntityCommandActuator.RemoveEntityCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RemoveEntityCommandActuator.class);

    public record RemoveEntityCommand(String entityName) {}

    @Override
    public Void execute(EventService<?> publisher, RemoveEntityCommand command) {
        Objects.requireNonNull(command.entityName(), "entityName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = EntityAggregate.deriveId(command.entityName());
        var payload = new EntityRemovedEvent(command.entityName());
        AggregateKey key = new AggregateKey(aggregateId, EntityAggregate.AGGREGATE_TYPE, tenantId);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.ENTITY_REMOVED, key, payload));
        LOG.info("Entity removed: {}", command.entityName());
        return null;
    }
}

