package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.EntityAggregate;
import com.koostack.operio.core.tenant.consent.events.EntityRegisteredEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterEntityCommandActuator
        implements Actuator<RegisterEntityCommandActuator.RegisterEntityCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RegisterEntityCommandActuator.class);

    public record RegisterEntityCommand(String entityName, String description) {}

    @Override
    public Void execute(EventService<?> publisher, RegisterEntityCommand command) {
        Objects.requireNonNull(command.entityName(), "entityName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = EntityAggregate.deriveId(command.entityName());
        var payload = new EntityRegisteredEvent(command.entityName(), command.description());
        AggregateKey key = new AggregateKey(aggregateId, EntityAggregate.AGGREGATE_TYPE, tenantId);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.ENTITY_REGISTERED, key, payload));
        LOG.info("Entity registered: {}", command.entityName());
        return null;
    }
}

