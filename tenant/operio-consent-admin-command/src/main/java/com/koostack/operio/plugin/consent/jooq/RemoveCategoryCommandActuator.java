package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.CategoryAggregate;
import com.koostack.operio.core.tenant.consent.events.CategoryRemovedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoveCategoryCommandActuator
        implements Actuator<RemoveCategoryCommandActuator.RemoveCategoryCommand, Void> {

    private static final Logger LOG = LoggerFactory.getLogger(RemoveCategoryCommandActuator.class);

    public record RemoveCategoryCommand(String categoryName) {}

    @Override
    public Void execute(EventService<?> publisher, RemoveCategoryCommand command) {
        Objects.requireNonNull(command.categoryName(), "categoryName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = CategoryAggregate.deriveId(command.categoryName());
        var payload = new CategoryRemovedEvent(command.categoryName());
        AggregateKey key =
                new AggregateKey(aggregateId, CategoryAggregate.AGGREGATE_TYPE, tenantId);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.CATEGORY_REMOVED, key, payload));
        LOG.info("Category removed: {}", command.categoryName());
        return null;
    }
}
