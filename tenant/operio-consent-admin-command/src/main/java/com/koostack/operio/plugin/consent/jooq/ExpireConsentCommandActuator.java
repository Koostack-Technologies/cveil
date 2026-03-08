package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.SubjectConsentAggregate;
import com.koostack.operio.core.tenant.consent.events.ConsentExpiredEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExpireConsentCommandActuator
        implements Actuator<ExpireConsentCommandActuator.ExpireConsentCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(ExpireConsentCommandActuator.class);

    public record ExpireConsentCommand(
            String subjectId,
            long policyId,
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName) {}

    @Override
    public Void execute(EventService<?> publisher, ExpireConsentCommand command) {
        Objects.requireNonNull(command.subjectId(), "subjectId must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectConsentAggregate.deriveId(command.subjectId());
        var payload =
                new ConsentExpiredEvent(
                        command.subjectId(),
                        command.policyId(),
                        command.purposeName(),
                        command.policyType(),
                        command.fieldName(),
                        command.entityName(),
                        command.categoryName());
        AggregateKey key =
                new AggregateKey(aggregateId, SubjectConsentAggregate.AGGREGATE_TYPE, tenantId);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.CONSENT_EXPIRED, key, payload));
        return null;
    }
}
