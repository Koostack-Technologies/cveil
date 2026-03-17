package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.cveil.core.platform.billing.events.InvoiceCreatedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Actuator that records an invoice creation (typically triggered by a webhook).
 *
 * <p>Publishes an {@link InvoiceCreatedEvent} to the platform event store.
 */
public class RecordInvoiceCommandActuator
        implements CommandActuator<RecordInvoiceCommandActuator.RecordInvoiceCommand, Void> {

    private final EventService<?> eventService;

    public RecordInvoiceCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record RecordInvoiceCommand(
            String tenantId,
            String externalInvoiceId,
            String externalSubscriptionId,
            String status,
            String currency,
            long amountDue,
            long subtotal,
            long total,
            String hostedInvoiceUrl,
            String invoicePdfUrl,
            Instant periodStart,
            Instant periodEnd,
            Instant dueDate) {}

    @Override
    public Void execute(RecordInvoiceCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new InvoiceCreatedEvent(
                        command.tenantId(),
                        command.externalInvoiceId(),
                        command.externalSubscriptionId(),
                        command.status(),
                        command.currency(),
                        command.amountDue(),
                        command.subtotal(),
                        command.total(),
                        command.hostedInvoiceUrl(),
                        command.invoicePdfUrl(),
                        command.periodStart(),
                        command.periodEnd(),
                        command.dueDate());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.INVOICE_CREATED,
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
