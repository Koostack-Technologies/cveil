package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.RegulationAggregate;
import com.koostack.cveil.core.tenant.consent.events.RegulationRegisteredEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterRegulationCommandActuator
        implements CommandActuator<
                RegisterRegulationCommandActuator.RegisterRegulationCommand, Void> {

    private final EventService<?> eventService;

    public RegisterRegulationCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(RegisterRegulationCommandActuator.class);

    public record RegisterRegulationCommand(String regulationName, String description) {}

    @Override
    public Void execute(RegisterRegulationCommand command) {
        Objects.requireNonNull(command.regulationName(), "regulationName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = RegulationAggregate.deriveId(command.regulationName());
        var payload =
                new RegulationRegisteredEvent(command.regulationName(), command.description());
        AggregateKey key =
                new AggregateKey(aggregateId, RegulationAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.REGULATION_REGISTERED, key, payload));
        LOG.info("Regulation registered: {}", command.regulationName());
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
