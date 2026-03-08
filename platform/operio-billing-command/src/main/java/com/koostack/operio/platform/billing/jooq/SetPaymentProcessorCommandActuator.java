package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.billing.BillingEventTypes;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.billing.events.PaymentProcessorSetEvent;
import java.util.UUID;

/**
 * Actuator that handles the "set payment processor" command.
 *
 * <p>Publishes a {@link PaymentProcessorSetEvent} to the platform event store.
 */
public class SetPaymentProcessorCommandActuator
        implements CommandActuator<SetPaymentProcessorCommandActuator.SetPaymentProcessorCommand, Void> {

    private final EventService<?> eventService;

    public SetPaymentProcessorCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }


    public record SetPaymentProcessorCommand(
            String tenantId, String processor, String customerId) {}

    @Override
    public Void execute(SetPaymentProcessorCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new PaymentProcessorSetEvent(
                        command.tenantId(), command.processor(), command.customerId());
        eventPublisher().publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID,
                        BillingEventTypes.PAYMENT_PROCESSOR_SET,
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
