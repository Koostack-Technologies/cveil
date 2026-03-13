package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.cveil.core.platform.billing.events.SubscriptionRenewedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Actuator that handles the "renew subscription" command.
 *
 * <p>Publishes a {@link SubscriptionRenewedEvent} to the platform event store.
 */
public class RenewSubscriptionCommandActuator
        implements CommandActuator<
                RenewSubscriptionCommandActuator.RenewSubscriptionCommand, Void> {

    private final EventService<?> eventService;

    public RenewSubscriptionCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record RenewSubscriptionCommand(
            String tenantId, Instant periodStart, Instant periodEnd) {}

    @Override
    public Void execute(RenewSubscriptionCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new SubscriptionRenewedEvent(
                        command.tenantId(), command.periodStart(), command.periodEnd());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.SUBSCRIPTION_RENEWED,
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
