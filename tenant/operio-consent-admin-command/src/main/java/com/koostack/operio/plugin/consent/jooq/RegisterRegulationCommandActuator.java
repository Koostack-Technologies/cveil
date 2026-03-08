package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.RegulationAggregate;
import com.koostack.operio.core.tenant.consent.events.RegulationRegisteredEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterRegulationCommandActuator
        implements CommandActuator<RegisterRegulationCommandActuator.RegisterRegulationCommand, Void> {

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
        eventPublisher().publishEvent(
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
