package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.core.CoreEventTypes;
import com.koostack.operio.core.platform.core.aggregates.TenantAggregate;
import com.koostack.operio.core.platform.core.events.TenantPlanChangedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "change tenant plan" command.
 *
 * <p>Publishes a {@link TenantPlanChangedEvent} to the platform event store.
 */
public class ChangeTenantPlanCommandActuator
        implements Actuator<ChangeTenantPlanCommandActuator.ChangeTenantPlanCommand, Void> {

    public record ChangeTenantPlanCommand(String tenantId, String oldPlan, String newPlan) {}

    @Override
    public Void execute(EventService<?> publisher, ChangeTenantPlanCommand command) {
        UUID aggregateId = TenantAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        TenantAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new TenantPlanChangedEvent(
                        command.tenantId(), command.oldPlan(), command.newPlan());
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        CoreEventTypes.TENANT_PLAN_CHANGED,
                        key,
                        payload));
        return null;
    }
}
