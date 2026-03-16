package com.koostack.cveil.platform.core.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.plan.PlanEventTypes;
import com.koostack.cveil.core.platform.plan.aggregates.PlanAggregate;
import com.koostack.cveil.core.platform.plan.events.PlanStripeSyncedEvent;
import java.util.UUID;

/**
 * Actuator that records the Stripe Product + Price IDs for a plan.
 *
 * <p>Publishes a {@link PlanStripeSyncedEvent} to the platform event store so that the core
 * projection handler can update the {@code cveilcore.plan} read model with the external
 * identifiers.
 */
public class SyncPlanStripeIdsCommandActuator
        implements CommandActuator<
                SyncPlanStripeIdsCommandActuator.SyncPlanStripeIdsCommand, Void> {

    private final EventService<?> eventService;

    public SyncPlanStripeIdsCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record SyncPlanStripeIdsCommand(
            String planId, String stripeProductId, String stripePriceId) {}

    @Override
    public Void execute(SyncPlanStripeIdsCommand command) {
        UUID aggregateId = PlanAggregate.deriveId(command.planId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        PlanAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new PlanStripeSyncedEvent(
                        command.planId(), command.stripeProductId(), command.stripePriceId());

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                PlanEventTypes.PLAN_STRIPE_SYNCED,
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
