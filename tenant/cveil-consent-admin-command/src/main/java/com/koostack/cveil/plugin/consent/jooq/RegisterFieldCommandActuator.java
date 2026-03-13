package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.FieldAggregate;
import com.koostack.cveil.core.tenant.consent.events.FieldRegisteredEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterFieldCommandActuator
        implements CommandActuator<RegisterFieldCommandActuator.RegisterFieldCommand, Void> {

    private final EventService<?> eventService;

    public RegisterFieldCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RegisterFieldCommandActuator.class);

    public record RegisterFieldCommand(
            String fieldName, String entityName, String description, String entityDescription) {}

    @Override
    public Void execute(RegisterFieldCommand command) {
        Objects.requireNonNull(command.fieldName(), "fieldName must not be null");
        String tenantId = TenantContext.require();
        String normalized = FieldAggregate.normalizeEntityName(command.entityName());
        UUID aggregateId = FieldAggregate.deriveId(normalized, command.fieldName());
        var payload =
                new FieldRegisteredEvent(
                        normalized,
                        command.fieldName(),
                        command.description(),
                        command.entityDescription());
        AggregateKey key = new AggregateKey(aggregateId, FieldAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.FIELD_REGISTERED, key, payload));
        LOG.info("Field registered: field={}, entity={}", command.fieldName(), normalized);
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
