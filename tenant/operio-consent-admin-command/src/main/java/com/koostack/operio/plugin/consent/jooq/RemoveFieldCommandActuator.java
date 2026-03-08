package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.FieldAggregate;
import com.koostack.operio.core.tenant.consent.events.FieldRemovedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoveFieldCommandActuator
        implements Actuator<RemoveFieldCommandActuator.RemoveFieldCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RemoveFieldCommandActuator.class);

    public record RemoveFieldCommand(String fieldName, String entityName) {}

    @Override
    public Void execute(EventService<?> publisher, RemoveFieldCommand command) {
        Objects.requireNonNull(command.fieldName(), "fieldName must not be null");
        String tenantId = TenantContext.require();
        String normalized = FieldAggregate.normalizeEntityName(command.entityName());
        UUID aggregateId = FieldAggregate.deriveId(normalized, command.fieldName());
        var payload = new FieldRemovedEvent(normalized, command.fieldName());
        AggregateKey key = new AggregateKey(aggregateId, FieldAggregate.AGGREGATE_TYPE, tenantId);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.FIELD_REMOVED, key, payload));
        LOG.info("Field removed: field={}, entity={}", command.fieldName(), normalized);
        return null;
    }
}

