package com.koostack.cveil.platform.core.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.core.CoreEventTypes;
import com.koostack.cveil.core.platform.core.aggregates.TenantAggregate;
import com.koostack.cveil.core.platform.core.events.TenantDisplayNameUpdatedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "update tenant display name" command.
 *
 * <p>Publishes a {@link TenantDisplayNameUpdatedEvent} to the platform event store.
 */
public class UpdateTenantDisplayNameCommandActuator
        implements CommandActuator<
                UpdateTenantDisplayNameCommandActuator.UpdateTenantDisplayNameCommand, Void> {

    private final EventService<?> eventService;

    public UpdateTenantDisplayNameCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record UpdateTenantDisplayNameCommand(String tenantId, String displayName) {}

    @Override
    public Void execute(UpdateTenantDisplayNameCommand command) {
        UUID aggregateId = TenantAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        TenantAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new TenantDisplayNameUpdatedEvent(command.tenantId(), command.displayName());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                CoreEventTypes.TENANT_DISPLAY_NAME_UPDATED,
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
