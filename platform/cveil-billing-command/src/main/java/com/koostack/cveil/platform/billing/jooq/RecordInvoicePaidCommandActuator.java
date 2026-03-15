package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.cveil.core.platform.billing.events.InvoicePaidEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Actuator that records an invoice payment (typically triggered by a webhook).
 *
 * <p>Publishes an {@link InvoicePaidEvent} to the platform event store.
 */
public class RecordInvoicePaidCommandActuator
        implements CommandActuator<
                RecordInvoicePaidCommandActuator.RecordInvoicePaidCommand, Void> {

    private final EventService<?> eventService;

    public RecordInvoicePaidCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record RecordInvoicePaidCommand(
            String tenantId, String externalInvoiceId, long amountPaid, Instant paidAt) {}

    @Override
    public Void execute(RecordInvoicePaidCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new InvoicePaidEvent(
                        command.tenantId(),
                        command.externalInvoiceId(),
                        command.amountPaid(),
                        command.paidAt());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.INVOICE_PAID,
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
