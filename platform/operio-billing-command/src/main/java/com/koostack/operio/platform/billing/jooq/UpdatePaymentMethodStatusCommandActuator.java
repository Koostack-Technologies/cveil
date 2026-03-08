package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.metrics.MetricPublisherService;
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
        implements CommandActuator<
                UpdatePaymentMethodStatusCommandActuator.UpdatePaymentMethodStatusCommand, Void> {

    private final EventService<?> eventService;

    public UpdatePaymentMethodStatusCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }


    public record UpdatePaymentMethodStatusCommand(String tenantId, String status) {}

    @Override
    public Void execute(UpdatePaymentMethodStatusCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new PaymentMethodStatusUpdatedEvent(command.tenantId(), command.status());
        eventPublisher().publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        BillingEventTypes.PAYMENT_METHOD_STATUS_UPDATED,
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
