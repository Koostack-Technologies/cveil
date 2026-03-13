package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.cveil.core.platform.billing.events.SubscriptionPlanChangedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "change subscription plan" command.
 *
 * <p>Publishes a {@link SubscriptionPlanChangedEvent} to the platform event store.
 */
public class ChangeSubscriptionPlanCommandActuator
        implements CommandActuator<
                ChangeSubscriptionPlanCommandActuator.ChangeSubscriptionPlanCommand, Void> {

    private final EventService<?> eventService;

    public ChangeSubscriptionPlanCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record ChangeSubscriptionPlanCommand(
            String tenantId,
            String oldPlanTier,
            String newPlanTier,
            int evalLimit,
            int subjectLimit,
            int burstLimit) {}

    @Override
    public Void execute(ChangeSubscriptionPlanCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new SubscriptionPlanChangedEvent(
                        command.tenantId(),
                        command.oldPlanTier(),
                        command.newPlanTier(),
                        command.evalLimit(),
                        command.subjectLimit(),
                        command.burstLimit());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.SUBSCRIPTION_PLAN_CHANGED,
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
