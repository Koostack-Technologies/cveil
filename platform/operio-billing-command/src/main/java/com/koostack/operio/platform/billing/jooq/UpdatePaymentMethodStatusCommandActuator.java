package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.billing.BillingEventTypes;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.billing.events.PaymentMethodStatusUpdatedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "update payment method status" command.
 *
 * <p>Publishes a {@link PaymentMethodStatusUpdatedEvent} to the platform event store.
 */
public class UpdatePaymentMethodStatusCommandActuator
        implements Actuator<
                UpdatePaymentMethodStatusCommandActuator.UpdatePaymentMethodStatusCommand, Void> {

    public record UpdatePaymentMethodStatusCommand(String tenantId, String status) {}

    @Override
    public Void execute(EventService<?> publisher, UpdatePaymentMethodStatusCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new PaymentMethodStatusUpdatedEvent(command.tenantId(), command.status());
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        BillingEventTypes.PAYMENT_METHOD_STATUS_UPDATED,
                        key,
                        payload));
        return null;
    }
}
