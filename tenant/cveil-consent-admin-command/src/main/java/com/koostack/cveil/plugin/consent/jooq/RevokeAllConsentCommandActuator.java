package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.SubjectConsentAggregate;
import com.koostack.cveil.core.tenant.consent.events.ConsentAllRevokedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RevokeAllConsentCommandActuator
        implements CommandActuator<RevokeAllConsentCommandActuator.RevokeAllConsentCommand, Void> {

    private final EventService<?> eventService;

    public RevokeAllConsentCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(RevokeAllConsentCommandActuator.class);

    public record RevokeAllConsentCommand(
            String subjectId, String actor, String ipAddress, String userAgent) {}

    @Override
    public Void execute(RevokeAllConsentCommand command) {
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
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.CONSENT_ALL_REVOKED, key, payload));
        LOG.info("Published bulk revoke event for subject: {}", command.subjectId());
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
