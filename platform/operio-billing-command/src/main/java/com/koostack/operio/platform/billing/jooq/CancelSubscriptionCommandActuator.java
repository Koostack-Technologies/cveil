package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.billing.BillingEventTypes;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.billing.events.SubscriptionCancelledEvent;
import java.util.UUID;

/**
 * Actuator that handles the "cancel subscription" command.
 *
 * <p>Publishes a {@link SubscriptionCancelledEvent} to the platform event store.
 */
public class CancelSubscriptionCommandActuator
        implements CommandActuator<
                CancelSubscriptionCommandActuator.CancelSubscriptionCommand, Void> {

    private final EventService<?> eventService;

    public CancelSubscriptionCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record CancelSubscriptionCommand(String tenantId) {}

    @Override
    public Void execute(CancelSubscriptionCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new SubscriptionCancelledEvent(command.tenantId());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.SUBSCRIPTION_CANCELLED,
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
