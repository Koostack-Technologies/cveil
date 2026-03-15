package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.cveil.core.platform.billing.events.PaymentMethodAttachedEvent;
import java.util.UUID;

/**
 * Actuator that handles attaching a payment method to a billing profile.
 *
 * <p>Publishes a {@link PaymentMethodAttachedEvent} to the platform event store.
 */
public class AttachPaymentMethodCommandActuator
        implements CommandActuator<
                AttachPaymentMethodCommandActuator.AttachPaymentMethodCommand, Void> {

    private final EventService<?> eventService;

    public AttachPaymentMethodCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record AttachPaymentMethodCommand(
            String tenantId,
            String externalPaymentMethodId,
            String type,
            String brand,
            String last4,
            int expMonth,
            int expYear,
            boolean isDefault) {}

    @Override
    public Void execute(AttachPaymentMethodCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new PaymentMethodAttachedEvent(
                        command.tenantId(),
                        command.externalPaymentMethodId(),
                        command.type(),
                        command.brand(),
                        command.last4(),
                        command.expMonth(),
                        command.expYear(),
                        command.isDefault());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.PAYMENT_METHOD_ATTACHED,
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
