package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.billing.BillingEventTypes;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.billing.events.SubscriptionCreatedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "create subscription" command.
 *
 * <p>Publishes a {@link SubscriptionCreatedEvent} to the platform event store.
 */
public class CreateSubscriptionCommandActuator
        implements Actuator<CreateSubscriptionCommandActuator.CreateSubscriptionCommand, Void> {

    public record CreateSubscriptionCommand(
            String tenantId,
            String planTier,
            int evalLimit,
            int subjectLimit,
            int burstLimit) {}

    @Override
    public Void execute(EventService<?> publisher, CreateSubscriptionCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new SubscriptionCreatedEvent(
                        command.tenantId(),
                        command.planTier(),
                        command.evalLimit(),
                        command.subjectLimit(),
                        command.burstLimit());
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        BillingEventTypes.SUBSCRIPTION_CREATED,
                        key,
                        payload));
        return null;
    }
}
