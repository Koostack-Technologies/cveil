package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.SubjectConsentAggregate;
import com.koostack.cveil.core.tenant.consent.events.ConsentRevokedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RevokeConsentCommandActuator
        implements CommandActuator<RevokeConsentCommandActuator.RevokeConsentCommand, Void> {

    private final EventService<?> eventService;

    public RevokeConsentCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RevokeConsentCommandActuator.class);

    public record RevokeConsentCommand(
            String subjectId,
            UUID policyId,
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName,
            String actor,
            String ipAddress,
            String userAgent) {}

    @Override
    public Void execute(RevokeConsentCommand command) {
        Objects.requireNonNull(command.subjectId(), "subjectId must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = SubjectConsentAggregate.deriveId(tenantId, command.subjectId());
        var payload =
                new ConsentRevokedEvent(
                        command.subjectId(),
                        command.policyId(),
                        command.purposeName(),
                        command.policyType(),
                        command.fieldName(),
                        command.entityName(),
                        command.categoryName(),
                        command.actor(),
                        command.ipAddress(),
                        command.userAgent());
        AggregateKey key =
                new AggregateKey(aggregateId, SubjectConsentAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.CONSENT_REVOKED, key, payload));
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
