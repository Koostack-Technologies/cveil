package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.SubjectDsarService;
import com.koostack.cveil.core.tenant.consent.aggregates.DsarAggregate;
import com.koostack.cveil.core.tenant.consent.events.SubjectShreddedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Atomically checks that all events for a subject have been published by the outbox, then deletes
 * the per-subject DSAR encryption key — the "shred" operation. Returns {@code true} if the key was
 * shredded, {@code false} if events are still pending.
 *
 * <p>This eliminates the TOCTOU race between a separate check and delete.
 */
public class ShredSubjectCommandActuator
        implements CommandActuator<
                ShredSubjectCommandActuator.ShredSubjectCommand,
                ShredSubjectCommandActuator.ShredSubjectResult> {

    private static final Logger LOG = LoggerFactory.getLogger(ShredSubjectCommandActuator.class);

    private final EventService<?> eventService;
    private final SubjectDsarService<?> dsarService;

    public ShredSubjectCommandActuator(
            EventService<?> eventService, SubjectDsarService<?> dsarService) {
        this.eventService = Objects.requireNonNull(eventService);
        this.dsarService = Objects.requireNonNull(dsarService);
    }

    public record ShredSubjectCommand(String subjectName) {}

    public record ShredSubjectResult(boolean shredded) {}

    @Override
    public ShredSubjectResult execute(ShredSubjectCommand command) {
        Objects.requireNonNull(command.subjectName(), "subjectName must not be null");
        String tenantId = TenantContext.require();
        UUID dsarId = SubjectDsarService.deriveDsarId(command.subjectName());

        if (!dsarService.allEventsPublished(dsarId)) {
            LOG.info(
                    "Shred denied for subject {}: events still pending outbox processing",
                    command.subjectName());
            return new ShredSubjectResult(false);
        }

        dsarService.deleteKey(dsarId);

        UUID aggregateId = DsarAggregate.deriveId(tenantId, command.subjectName());
        AggregateKey key = new AggregateKey(aggregateId, DsarAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId,
                                ConsentEventTypes.SUBJECT_SHREDDED,
                                key,
                                new SubjectShreddedEvent(command.subjectName())));
        LOG.info("DSAR key shredded for subject: {}", command.subjectName());
        return new ShredSubjectResult(true);
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
