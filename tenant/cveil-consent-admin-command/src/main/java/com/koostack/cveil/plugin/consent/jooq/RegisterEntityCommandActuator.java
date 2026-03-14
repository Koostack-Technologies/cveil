package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.EntityAggregate;
import com.koostack.cveil.core.tenant.consent.events.EntityRegisteredEvent;
import com.koostack.cveil.core.util.FieldNameNormalizer;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterEntityCommandActuator
        implements CommandActuator<RegisterEntityCommandActuator.RegisterEntityCommand, Void> {

    private final EventService<?> eventService;

    public RegisterEntityCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RegisterEntityCommandActuator.class);

    public record RegisterEntityCommand(String entityName, String description) {}

    @Override
    public Void execute(RegisterEntityCommand command) {
        Objects.requireNonNull(command.entityName(), "entityName must not be null");
        String tenantId = TenantContext.require();
        String normalized = FieldNameNormalizer.normalize(command.entityName());
        if (normalized == null) normalized = command.entityName();
        UUID aggregateId = EntityAggregate.deriveId(normalized);
        var payload = new EntityRegisteredEvent(normalized, command.description());
        AggregateKey key = new AggregateKey(aggregateId, EntityAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.ENTITY_REGISTERED, key, payload));
        LOG.info("Entity registered: {}", normalized);
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
