package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.SubjectAggregate;
import com.koostack.operio.core.tenant.consent.events.SubjectRegisteredEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterSubjectCommandActuator
        implements Actuator<RegisterSubjectCommandActuator.RegisterSubjectCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RegisterSubjectCommandActuator.class);

    public record RegisterSubjectCommand(String subjectName, String description) {}

    @Override
    public Void execute(EventService<?> publisher, RegisterSubjectCommand command) {
        Objects.requireNonNull(command.subjectName(), "subjectName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectAggregate.deriveId(command.subjectName());
        var payload = new SubjectRegisteredEvent(command.subjectName(), command.description());
        AggregateKey key = new AggregateKey(aggregateId, SubjectAggregate.AGGREGATE_TYPE, tenantId);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.SUBJECT_REGISTERED, key, payload));
        LOG.info("Subject registered: {}", command.subjectName());
        return null;
    }
}
