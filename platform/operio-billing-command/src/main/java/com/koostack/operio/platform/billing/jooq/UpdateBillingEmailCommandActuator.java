package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.billing.BillingEventTypes;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.billing.events.BillingEmailUpdatedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "update billing email" command.
 *
 * <p>Publishes a {@link BillingEmailUpdatedEvent} to the platform event store.
 */
public class UpdateBillingEmailCommandActuator
        implements Actuator<UpdateBillingEmailCommandActuator.UpdateBillingEmailCommand, Void> {

    public record UpdateBillingEmailCommand(String tenantId, String newEmail) {}

    @Override
    public Void execute(EventService<?> publisher, UpdateBillingEmailCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new BillingEmailUpdatedEvent(command.tenantId(), command.newEmail());
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        BillingEventTypes.BILLING_EMAIL_UPDATED,
                        key,
                        payload));
        return null;
    }
}
