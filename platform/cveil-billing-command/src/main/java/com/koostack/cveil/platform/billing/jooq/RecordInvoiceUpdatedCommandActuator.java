package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.cveil.core.platform.billing.events.InvoiceUpdatedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Actuator that records an invoice update (typically triggered by a webhook).
 *
 * <p>Publishes an {@link InvoiceUpdatedEvent} to the platform event store.
 */
public class RecordInvoiceUpdatedCommandActuator
        implements CommandActuator<
                RecordInvoiceUpdatedCommandActuator.RecordInvoiceUpdatedCommand, Void> {

    private final EventService<?> eventService;

    public RecordInvoiceUpdatedCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record RecordInvoiceUpdatedCommand(
            String tenantId,
            String externalInvoiceId,
            String status,
            long amountDue,
            long amountPaid,
            long amountRemaining,
            long total,
            String hostedInvoiceUrl,
            String invoicePdfUrl,
            Instant paidAt) {}

    @Override
    public Void execute(RecordInvoiceUpdatedCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new InvoiceUpdatedEvent(
                        command.tenantId(),
                        command.externalInvoiceId(),
                        command.status(),
                        command.amountDue(),
                        command.amountPaid(),
                        command.amountRemaining(),
                        command.total(),
                        command.hostedInvoiceUrl(),
                        command.invoicePdfUrl(),
                        command.paidAt());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.INVOICE_UPDATED,
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
