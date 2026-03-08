package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
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
        implements Actuator<ActivateTenantCommandActuator.ActivateTenantCommand, Void> {

    public record ActivateTenantCommand(String tenantId) {}

    @Override
    public Void execute(EventService<?> publisher, ActivateTenantCommand command) {
        UUID aggregateId = TenantAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        TenantAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new TenantActivatedEvent(command.tenantId());
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        CoreEventTypes.TENANT_ACTIVATED,
                        key,
                        payload));
        return null;
    }
}
