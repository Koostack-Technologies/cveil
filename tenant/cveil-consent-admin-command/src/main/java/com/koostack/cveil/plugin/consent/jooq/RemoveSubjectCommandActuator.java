package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.SubjectAggregate;
import com.koostack.cveil.core.tenant.consent.events.SubjectRemovedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoveSubjectCommandActuator
        implements CommandActuator<RemoveSubjectCommandActuator.RemoveSubjectCommand, Void> {

    private final EventService<?> eventService;

    public RemoveSubjectCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RemoveSubjectCommandActuator.class);

    public record RemoveSubjectCommand(String subjectName) {}

    @Override
    public Void execute(RemoveSubjectCommand command) {
        Objects.requireNonNull(command.subjectName(), "subjectName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectAggregate.deriveId(tenantId, command.subjectName());
        var payload = new SubjectRemovedEvent(command.subjectName());
        AggregateKey key = new AggregateKey(aggregateId, SubjectAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.SUBJECT_REMOVED, key, payload));
        LOG.info("Subject removed: {}", command.subjectName());
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
