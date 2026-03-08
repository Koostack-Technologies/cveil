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
 * <p>Hydrates the {@link TenantAggregate} from the event log to resolve the current (old) plan,
 * then publishes a {@link TenantPlanChangedEvent} to the platform event store.
 */
public class ChangeTenantPlanCommandActuator
        implements Actuator<ChangeTenantPlanCommandActuator.ChangeTenantPlanCommand, Void> {

    public record ChangeTenantPlanCommand(String tenantId, String newPlan) {}

    @Override
    public Void execute(EventService<?> publisher, ChangeTenantPlanCommand command) {
        UUID aggregateId = TenantAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        TenantAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        // Hydrate the tenant aggregate from the event log to resolve the current plan
        TenantAggregate current = publisher.hydrateAggregate(key, TenantAggregate.class);
        String oldPlan = current != null ? current.plan() : null;

        var payload = new TenantPlanChangedEvent(command.tenantId(), oldPlan, command.newPlan());
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        CoreEventTypes.TENANT_PLAN_CHANGED,
                        key,
                        payload));
        return null;
    }
}
