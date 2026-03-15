package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.cveil.core.platform.billing.events.PaymentMethodDefaultChangedEvent;
import java.util.UUID;

/**
 * Actuator that handles changing the default payment method.
 *
 * <p>Publishes a {@link PaymentMethodDefaultChangedEvent} to the platform event store.
 */
public class SetDefaultPaymentMethodCommandActuator
        implements CommandActuator<
                SetDefaultPaymentMethodCommandActuator.SetDefaultPaymentMethodCommand, Void> {

    private final EventService<?> eventService;

    public SetDefaultPaymentMethodCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record SetDefaultPaymentMethodCommand(String tenantId, String externalPaymentMethodId) {}

    @Override
    public Void execute(SetDefaultPaymentMethodCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new PaymentMethodDefaultChangedEvent(
                        command.tenantId(), command.externalPaymentMethodId());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.PAYMENT_METHOD_DEFAULT_CHANGED,
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
