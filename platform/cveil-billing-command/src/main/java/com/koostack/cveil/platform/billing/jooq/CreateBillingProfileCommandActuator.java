package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.cveil.core.platform.billing.events.BillingProfileCreatedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "create billing profile" command.
 *
 * <p>Publishes a {@link BillingProfileCreatedEvent} to the platform event store.
 */
public class CreateBillingProfileCommandActuator
        implements CommandActuator<
                CreateBillingProfileCommandActuator.CreateBillingProfileCommand, Void> {

    private final EventService<?> eventService;

    public CreateBillingProfileCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record CreateBillingProfileCommand(String tenantId, String billingEmail) {}

    @Override
    public Void execute(CreateBillingProfileCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new BillingProfileCreatedEvent(command.tenantId(), command.billingEmail());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.BILLING_PROFILE_CREATED,
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
