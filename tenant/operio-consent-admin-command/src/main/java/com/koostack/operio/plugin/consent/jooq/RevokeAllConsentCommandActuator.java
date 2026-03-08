package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.SubjectConsentAggregate;
import com.koostack.operio.core.tenant.consent.events.ConsentAllRevokedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RevokeAllConsentCommandActuator
        implements Actuator<RevokeAllConsentCommandActuator.RevokeAllConsentCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(RevokeAllConsentCommandActuator.class);

    public record RevokeAllConsentCommand(
            String subjectId, String actor, String ipAddress, String userAgent) {}

    @Override
    public Void execute(EventService<?> publisher, RevokeAllConsentCommand command) {
        Objects.requireNonNull(command.subjectId(), "subjectId must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectConsentAggregate.deriveId(command.subjectId());
        var payload =
                new ConsentAllRevokedEvent(
                        command.subjectId(),
                        command.actor(),
                        command.ipAddress(),
                        command.userAgent());
        AggregateKey key =
                new AggregateKey(aggregateId, SubjectConsentAggregate.AGGREGATE_TYPE, tenantId);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.CONSENT_ALL_REVOKED, key, payload));
        LOG.info("Published bulk revoke event for subject: {}", command.subjectId());
        return null;
    }
}
