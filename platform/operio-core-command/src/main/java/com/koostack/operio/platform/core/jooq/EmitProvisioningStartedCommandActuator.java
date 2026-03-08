package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import com.koostack.operio.core.platform.core.aggregates.TenantAggregate;
import com.koostack.operio.core.platform.core.events.TenantProvisioningStartedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "emit provisioning started" command.
 *
 * <p>Publishes a {@link TenantProvisioningStartedEvent} to signal that async infrastructure
 * provisioning should begin.
 */
public class EmitProvisioningStartedCommandActuator
        implements CommandActuator<
                EmitProvisioningStartedCommandActuator.EmitProvisioningStartedCommand, Void> {

    private final EventService<?> eventService;

    public EmitProvisioningStartedCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }


    public record EmitProvisioningStartedCommand(
            String tenantId, String displayName, String plan) {}

    @Override
    public Void execute(EmitProvisioningStartedCommand command) {
        UUID aggregateId = TenantAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        TenantAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new TenantProvisioningStartedEvent(
                        command.tenantId(), command.displayName(), command.plan());
        eventPublisher().publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        CoreEventTypes.TENANT_PROVISIONING_STARTED,
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
