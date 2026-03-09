package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.billing.BillingEventTypes;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.billing.events.SubscriptionCreatedEvent;
import com.koostack.operio.core.platform.plan.PlanQueryService;
import java.util.UUID;

/**
 * Actuator that handles the "create subscription" command.
 *
 * <p>Validates the requested plan via {@link PlanQueryService} (SQL-backed) to ensure it exists and
 * is active, hydrates the {@link BillingProfileAggregate} to verify no subscription already exists,
 * then publishes a {@link SubscriptionCreatedEvent} to the platform event store.
 */
public class CreateSubscriptionCommandActuator
        implements CommandActuator<
                CreateSubscriptionCommandActuator.CreateSubscriptionCommand, Void> {

    private final EventService<?> eventService;
    private final PlanQueryService<?> planQueryService;

    public CreateSubscriptionCommandActuator(
            EventService<?> eventService, PlanQueryService<?> planQueryService) {
        this.eventService = eventService;
        this.planQueryService = planQueryService;
    }

    public record CreateSubscriptionCommand(String tenantId, String planTier) {}

    @Override
    public Void execute(CreateSubscriptionCommand command) {
        // Validate plan existence and active status via SQL-backed query service
        PlanQueryService.PlanInfo plan = planQueryService.getPlan(command.planTier());
        if (plan == null) {
            throw new OperioServiceException("Plan '" + command.planTier() + "' does not exist");
        }
        if (!plan.isActive()) {
            throw new OperioServiceException("Plan '" + command.planTier() + "' is not active");
        }

        // Hydrate billing profile to check for existing subscription
        UUID billingAggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey billingKey =
                new AggregateKey(
                        billingAggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        BillingProfileAggregate existing =
                eventPublisher().hydrateAggregate(billingKey, BillingProfileAggregate.class);
        if (existing != null && existing.subscription() != null) {
            throw new OperioServiceException(
                    "Tenant '" + command.tenantId() + "' already has an active subscription");
        }

        var payload =
                new SubscriptionCreatedEvent(
                        command.tenantId(),
                        command.planTier(),
                        Math.toIntExact(plan.evalLimit()),
                        Math.toIntExact(plan.subjectLimit()),
                        plan.burstLimit());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.SUBSCRIPTION_CREATED,
                                billingKey,
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
