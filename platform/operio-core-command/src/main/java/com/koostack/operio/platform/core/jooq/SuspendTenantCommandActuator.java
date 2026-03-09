package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import com.koostack.operio.core.platform.core.aggregates.TenantAggregate;
import com.koostack.operio.core.platform.core.events.TenantSuspendedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "suspend tenant" command.
 *
 * <p>Publishes a {@link TenantSuspendedEvent} to the platform event store.
 */
public class SuspendTenantCommandActuator
        implements CommandActuator<SuspendTenantCommandActuator.SuspendTenantCommand, Void> {

    private final EventService<?> eventService;

    public SuspendTenantCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record SuspendTenantCommand(String tenantId, String reason) {}

    @Override
    public Void execute(SuspendTenantCommand command) {
        UUID aggregateId = TenantAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        TenantAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new TenantSuspendedEvent(command.tenantId(), command.reason());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                CoreEventTypes.TENANT_SUSPENDED,
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
