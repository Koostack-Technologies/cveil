package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.cveil.core.platform.billing.events.UsageReportedEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Actuator that records a metered usage report to the event store.
 *
 * <p>Publishes a {@link UsageReportedEvent} to the platform event store.
 */
public class RecordUsageReportedCommandActuator
        implements CommandActuator<
                RecordUsageReportedCommandActuator.RecordUsageReportedCommand, Void> {

    private final EventService<?> eventService;

    public RecordUsageReportedCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record RecordUsageReportedCommand(
            String tenantId, String metricName, long quantity, Instant reportedAt) {}

    @Override
    public Void execute(RecordUsageReportedCommand command) {
        UUID aggregateId = BillingProfileAggregate.deriveId(command.tenantId());
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);

        var payload =
                new UsageReportedEvent(
                        command.tenantId(),
                        command.metricName(),
                        command.quantity(),
                        command.reportedAt());
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                PlatformConstants.PLATFORM_TENANT_ID,
                                BillingEventTypes.USAGE_REPORTED,
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
