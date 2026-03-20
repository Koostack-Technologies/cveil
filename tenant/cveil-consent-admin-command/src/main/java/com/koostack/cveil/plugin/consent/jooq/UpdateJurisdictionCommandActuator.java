package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.JurisdictionAggregate;
import com.koostack.cveil.core.tenant.consent.events.JurisdictionUpdatedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateJurisdictionCommandActuator
        implements CommandActuator<
                UpdateJurisdictionCommandActuator.UpdateJurisdictionCommand, Void> {

    private final EventService<?> eventService;

    public UpdateJurisdictionCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(UpdateJurisdictionCommandActuator.class);

    public record UpdateJurisdictionCommand(String jurisdictionName, String description) {}

    @Override
    public Void execute(UpdateJurisdictionCommand command) {
        Objects.requireNonNull(command.jurisdictionName(), "jurisdictionName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = JurisdictionAggregate.deriveId(tenantId, command.jurisdictionName());
        var payload =
                new JurisdictionUpdatedEvent(command.jurisdictionName(), command.description());
        AggregateKey key =
                new AggregateKey(aggregateId, JurisdictionAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.JURISDICTION_UPDATED, key, payload));
        LOG.info("Jurisdiction updated: {}", command.jurisdictionName());
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
