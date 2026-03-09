package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.billing.BillingEventTypes;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.billing.events.BillingTaxIdUpdatedEvent;
import java.util.UUID;

/**
 * Actuator that handles the "update tax ID" command.
 *
 * <p>Publishes a {@link BillingTaxIdUpdatedEvent} to the platform event store.
 */
public class UpdateTaxIdCommandActuator
        implements CommandActuator<UpdateTaxIdCommandActuator.UpdateTaxIdCommand, Void> {

    private final EventService<?> eventService;

    public UpdateTaxIdCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record UpdateTaxIdCommand(String tenantId, String taxId) {}

    @Override
    public Void execute(UpdateTaxIdCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload = new BillingTaxIdUpdatedEvent(command.tenantId(), command.taxId());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.BILLING_TAX_ID_UPDATED,
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
