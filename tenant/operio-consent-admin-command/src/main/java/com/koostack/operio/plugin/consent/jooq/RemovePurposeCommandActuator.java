package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.PurposeAggregate;
import com.koostack.operio.core.tenant.consent.events.PurposeRemovedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemovePurposeCommandActuator
        implements Actuator<RemovePurposeCommandActuator.RemovePurposeCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RemovePurposeCommandActuator.class);

    public record RemovePurposeCommand(String purposeName) {}

    @Override
    public Void execute(EventService<?> publisher, RemovePurposeCommand command) {
        Objects.requireNonNull(command.purposeName(), "purposeName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = PurposeAggregate.deriveId(command.purposeName());
        var payload = new PurposeRemovedEvent(command.purposeName());
        AggregateKey key =
                new AggregateKey(aggregateId, PurposeAggregate.AGGREGATE_TYPE, tenantId);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.PURPOSE_REMOVED, key, payload));
        LOG.info("Purpose removed: {}", command.purposeName());
        return null;
    }
}

