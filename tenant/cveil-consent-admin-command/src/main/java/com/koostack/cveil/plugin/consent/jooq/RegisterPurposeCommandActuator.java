package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.PurposeAggregate;
import com.koostack.cveil.core.tenant.consent.events.PurposeRegisteredEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterPurposeCommandActuator
        implements CommandActuator<RegisterPurposeCommandActuator.RegisterPurposeCommand, Void> {

    private final EventService<?> eventService;

    public RegisterPurposeCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RegisterPurposeCommandActuator.class);

    public record RegisterPurposeCommand(String purposeName, String description) {}

    @Override
    public Void execute(RegisterPurposeCommand command) {
        Objects.requireNonNull(command.purposeName(), "purposeName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(tenantId, command.purposeName());
        var payload = new PurposeRegisteredEvent(command.purposeName(), command.description());
        AggregateKey key = new AggregateKey(aggregateId, PurposeAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.PURPOSE_REGISTERED, key, payload));
        LOG.info("Purpose registered: {}", command.purposeName());
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
