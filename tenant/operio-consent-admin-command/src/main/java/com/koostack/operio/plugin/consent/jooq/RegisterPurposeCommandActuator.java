package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.tenant.consent.events.PurposeRegisteredEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterPurposeCommandActuator
        implements Actuator<RegisterPurposeCommandActuator.RegisterPurposeCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RegisterPurposeCommandActuator.class);

    public record RegisterPurposeCommand(String purposeName, String description) {}

    @Override
    public Void execute(EventService<?> publisher, RegisterPurposeCommand command) {
        Objects.requireNonNull(command.purposeName(), "purposeName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(command.purposeName());
        var payload = new PurposeRegisteredEvent(command.purposeName(), command.description());
        AggregateKey key = new AggregateKey(aggregateId, PurposeAggregate.AGGREGATE_TYPE, tenantId);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.PURPOSE_REGISTERED, key, payload));
        LOG.info("Purpose registered: {}", command.purposeName());
        return null;
    }
}
