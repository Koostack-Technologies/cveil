package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.billing.BillingEventTypes;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.billing.events.SubscriptionRenewedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Actuator that handles the "renew subscription" command.
 *
 * <p>Publishes a {@link SubscriptionRenewedEvent} to the platform event store.
 */
public class RenewSubscriptionCommandActuator
        implements Actuator<RenewSubscriptionCommandActuator.RenewSubscriptionCommand, Void> {

    public record RenewSubscriptionCommand(
            String tenantId, Instant periodStart, Instant periodEnd) {}

    @Override
    public Void execute(EventService<?> publisher, RenewSubscriptionCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new SubscriptionRenewedEvent(
                        command.tenantId(), command.periodStart(), command.periodEnd());
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        BillingEventTypes.SUBSCRIPTION_RENEWED,
                        key,
                        payload));
        return null;
    }
}
