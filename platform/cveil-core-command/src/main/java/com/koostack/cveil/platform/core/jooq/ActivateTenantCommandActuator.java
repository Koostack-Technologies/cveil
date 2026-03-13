package com.koostack.cveil.platform.core.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.core.CoreEventTypes;
import com.koostack.cveil.core.platform.core.aggregates.TenantAggregate;
import com.koostack.cveil.core.platform.core.events.TenantActivatedEvent;
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
