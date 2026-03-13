package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
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
        implements CommandActuator<
                UpdateBillingEmailCommandActuator.UpdateBillingEmailCommand, Void> {

    private final EventService<?> eventService;

    public UpdateBillingEmailCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record UpdateBillingEmailCommand(String tenantId, String newEmail) {}

    @Override
    public Void execute(UpdateBillingEmailCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new BillingEmailUpdatedEvent(command.tenantId(), command.newEmail());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.BILLING_EMAIL_UPDATED,
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
