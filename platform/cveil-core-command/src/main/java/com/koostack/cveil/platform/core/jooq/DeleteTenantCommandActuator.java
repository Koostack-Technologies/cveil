package com.koostack.cveil.platform.core.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.core.CoreEventTypes;
import com.koostack.cveil.core.platform.core.aggregates.TenantAggregate;
import com.koostack.cveil.core.platform.core.events.TenantDeletedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "delete tenant" command.
 *
 * <p>Publishes a {@link TenantDeletedEvent} to the platform event store.
 */
public class DeleteTenantCommandActuator
        implements CommandActuator<DeleteTenantCommandActuator.DeleteTenantCommand, Void> {

    private final EventService<?> eventService;

    public DeleteTenantCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record DeleteTenantCommand(String tenantId) {}

    @Override
    public Void execute(DeleteTenantCommand command) {
        UUID aggregateId = TenantAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        TenantAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new TenantDeletedEvent(command.tenantId());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                CoreEventTypes.TENANT_DELETED,
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
