package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.DsarAggregate;
import com.koostack.cveil.core.tenant.consent.events.SubjectAuditDeletedEvent;
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
        UUID aggregateId = DsarAggregate.deriveId(tenantId, command.subjectId());
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
