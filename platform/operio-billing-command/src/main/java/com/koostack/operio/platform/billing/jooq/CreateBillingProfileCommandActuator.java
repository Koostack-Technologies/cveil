package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.billing.BillingEventTypes;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.billing.events.BillingProfileCreatedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "create billing profile" command.
 *
 * <p>Publishes a {@link BillingProfileCreatedEvent} to the platform event store.
 */
public class CreateBillingProfileCommandActuator
        implements Actuator<
                CreateBillingProfileCommandActuator.CreateBillingProfileCommand, Void> {

    public record CreateBillingProfileCommand(String tenantId, String billingEmail) {}

    @Override
    public Void execute(EventService<?> publisher, CreateBillingProfileCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new BillingProfileCreatedEvent(command.tenantId(), command.billingEmail());
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        BillingEventTypes.BILLING_PROFILE_CREATED,
                        key,
                        payload));
        return null;
    }
}
