package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.DsarAggregate;
import com.koostack.cveil.core.tenant.consent.events.SubjectDataDeletedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes a {@link ConsentEventTypes#SUBJECT_DATA_DELETED} domain event after the multi-step DSAR
 * erasure saga completes. This composite event summarises the deletion outcome and is consumed by
 * outbound webhooks and external integrations.
 */
public class PublishSubjectDataDeletedCommandActuator
        implements CommandActuator<
                PublishSubjectDataDeletedCommandActuator.PublishSubjectDataDeletedCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(PublishSubjectDataDeletedCommandActuator.class);

    private final EventService<?> eventService;

    public PublishSubjectDataDeletedCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    public record PublishSubjectDataDeletedCommand(
            String subjectId,
            String deletionRequestId,
            long ledgerEntriesRevoked,
            long cachedKeysDeleted,
            String status,
            String actor,
            String ipAddress,
            String userAgent) {}

    @Override
    public Void execute(PublishSubjectDataDeletedCommand command) {
        Objects.requireNonNull(command.subjectId(), "subjectId must not be null");
        Objects.requireNonNull(command.deletionRequestId(), "deletionRequestId must not be null");

        String tenantId = TenantContext.require();
        UUID aggregateId = DsarAggregate.deriveId(tenantId, command.subjectId());
        AggregateKey key = new AggregateKey(aggregateId, DsarAggregate.AGGREGATE_TYPE, tenantId);

        var payload =
                new SubjectDataDeletedEvent(
                        command.subjectId(),
                        command.deletionRequestId(),
                        command.ledgerEntriesRevoked(),
                        command.cachedKeysDeleted(),
                        command.status(),
                        command.actor(),
                        command.ipAddress(),
                        command.userAgent());

        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.SUBJECT_DATA_DELETED, key, payload));

        LOG.info(
                "Published SUBJECT_DATA_DELETED event for subject {} (request {})",
                command.subjectId(),
                command.deletionRequestId());
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
