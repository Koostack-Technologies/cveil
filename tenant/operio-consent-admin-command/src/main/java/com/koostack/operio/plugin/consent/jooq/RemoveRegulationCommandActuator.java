package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.RegulationAggregate;
import com.koostack.operio.core.tenant.consent.events.RegulationRemovedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoveRegulationCommandActuator
        implements CommandActuator<RemoveRegulationCommandActuator.RemoveRegulationCommand, Void> {

    private final EventService<?> eventService;

    public RemoveRegulationCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(RemoveRegulationCommandActuator.class);

    public record RemoveRegulationCommand(String regulationName) {}

    @Override
    public Void execute(RemoveRegulationCommand command) {
        Objects.requireNonNull(command.regulationName(), "regulationName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = RegulationAggregate.deriveId(command.regulationName());
        var payload = new RegulationRemovedEvent(command.regulationName());
        AggregateKey key =
                new AggregateKey(aggregateId, RegulationAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.REGULATION_REMOVED, key, payload));
        LOG.info("Regulation removed: {}", command.regulationName());
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
