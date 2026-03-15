package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.cveil.core.platform.billing.events.PaymentMethodDetachedEvent;
import java.util.UUID;

/**
 * Actuator that handles detaching a payment method from a billing profile.
 *
 * <p>Publishes a {@link PaymentMethodDetachedEvent} to the platform event store.
 */
public class DetachPaymentMethodCommandActuator
        implements CommandActuator<
                DetachPaymentMethodCommandActuator.DetachPaymentMethodCommand, Void> {

    private final EventService<?> eventService;

    public DetachPaymentMethodCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record DetachPaymentMethodCommand(String tenantId, String externalPaymentMethodId) {}

    @Override
    public Void execute(DetachPaymentMethodCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new PaymentMethodDetachedEvent(
                        command.tenantId(), command.externalPaymentMethodId());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.PAYMENT_METHOD_DETACHED,
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
