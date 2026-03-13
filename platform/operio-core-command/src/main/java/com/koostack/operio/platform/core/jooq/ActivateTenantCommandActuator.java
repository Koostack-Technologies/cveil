package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import com.koostack.operio.core.platform.core.aggregates.TenantAggregate;
import com.koostack.operio.core.platform.core.events.TenantActivatedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "activate tenant" command.
 *
 * <p>Publishes a {@link TenantActivatedEvent} to the platform event store.
 */
public class ActivateTenantCommandActuator
        implements CommandActuator<ActivateTenantCommandActuator.ActivateTenantCommand, Void> {

    private final EventService<?> eventService;

    public ActivateTenantCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record ActivateTenantCommand(String tenantId) {}

    @Override
    public Void execute(ActivateTenantCommand command) {
        UUID aggregateId = TenantAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        TenantAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new TenantActivatedEvent(command.tenantId());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                CoreEventTypes.TENANT_ACTIVATED,
                                key,
                                payload));
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
