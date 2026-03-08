package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.SubjectAggregate;
import com.koostack.operio.core.tenant.consent.events.SubjectUpdatedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateSubjectCommandActuator
        implements CommandActuator<UpdateSubjectCommandActuator.UpdateSubjectCommand, Void> {

    private final EventService<?> eventService;

    public UpdateSubjectCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }


    private static final Logger LOG = LoggerFactory.getLogger(UpdateSubjectCommandActuator.class);

    public record UpdateSubjectCommand(String subjectName, String description) {}

    @Override
    public Void execute(UpdateSubjectCommand command) {
        Objects.requireNonNull(command.subjectName(), "subjectName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectAggregate.deriveId(command.subjectName());
        var payload = new SubjectUpdatedEvent(command.subjectName(), command.description());
        AggregateKey key = new AggregateKey(aggregateId, SubjectAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher().publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.SUBJECT_UPDATED, key, payload));
        LOG.info("Subject updated: {}", command.subjectName());
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
