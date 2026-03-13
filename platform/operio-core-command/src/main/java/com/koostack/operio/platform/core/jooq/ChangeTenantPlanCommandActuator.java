package com.koostack.operio.platform.core.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
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
        implements CommandActuator<ChangeTenantPlanCommandActuator.ChangeTenantPlanCommand, Void> {

    private final EventService<?> eventService;

    public ChangeTenantPlanCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record ChangeTenantPlanCommand(String tenantId, String newPlan) {}

    @Override
    public Void execute(ChangeTenantPlanCommand command) {
        UUID aggregateId = TenantAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        TenantAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        // Hydrate the tenant aggregate from the event log to resolve the current plan
        TenantAggregate current = eventPublisher().hydrateAggregate(key, TenantAggregate.class);
        String oldPlan = current != null ? current.plan() : null;

        var payload = new TenantPlanChangedEvent(command.tenantId(), oldPlan, command.newPlan());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                CoreEventTypes.TENANT_PLAN_CHANGED,
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
