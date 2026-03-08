package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.JurisdictionAggregate;
import com.koostack.operio.core.tenant.consent.events.JurisdictionRemovedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoveJurisdictionCommandActuator
        implements CommandActuator<RemoveJurisdictionCommandActuator.RemoveJurisdictionCommand, Void> {

    private final EventService<?> eventService;

    public RemoveJurisdictionCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }


    private static final Logger LOG =
            LoggerFactory.getLogger(RemoveJurisdictionCommandActuator.class);

    public record RemoveJurisdictionCommand(String jurisdictionName) {}

    @Override
    public Void execute(RemoveJurisdictionCommand command) {
        Objects.requireNonNull(command.jurisdictionName(), "jurisdictionName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = JurisdictionAggregate.deriveId(command.jurisdictionName());
        var payload = new JurisdictionRemovedEvent(command.jurisdictionName());
        AggregateKey key =
                new AggregateKey(aggregateId, JurisdictionAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher().publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.JURISDICTION_REMOVED, key, payload));
        LOG.info("Jurisdiction removed: {}", command.jurisdictionName());
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
