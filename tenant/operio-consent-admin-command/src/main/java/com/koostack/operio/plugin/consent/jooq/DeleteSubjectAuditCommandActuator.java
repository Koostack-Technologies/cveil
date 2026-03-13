package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.DsarAggregate;
import com.koostack.operio.core.tenant.consent.events.SubjectAuditDeletedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeleteSubjectAuditCommandActuator
        implements CommandActuator<
                DeleteSubjectAuditCommandActuator.DeleteSubjectAuditCommand, Void> {

    private final EventService<?> eventService;

    public DeleteSubjectAuditCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DeleteSubjectAuditCommandActuator.class);

    public record DeleteSubjectAuditCommand(
            String subjectId,
            String deletionRequestId,
            String actor,
            String ipAddress,
            String userAgent) {}

    @Override
    public Void execute(DeleteSubjectAuditCommand command) {
        Objects.requireNonNull(command.subjectId(), "subjectId must not be null");
        Objects.requireNonNull(command.deletionRequestId(), "deletionRequestId must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = DsarAggregate.deriveId(command.subjectId());
        var payload =
                new SubjectAuditDeletedEvent(
                        command.subjectId(),
                        command.deletionRequestId(),
                        command.actor(),
                        command.ipAddress(),
                        command.userAgent());
        AggregateKey key = new AggregateKey(aggregateId, DsarAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.SUBJECT_AUDIT_DELETED, key, payload));
        LOG.info("Published DSAR audit deletion event for subject: {}", command.subjectId());
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
