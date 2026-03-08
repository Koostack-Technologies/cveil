package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.Actuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.CategoryAggregate;
import com.koostack.operio.core.tenant.consent.events.CategoryRegisteredEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterCategoryCommandActuator
        implements Actuator<RegisterCategoryCommandActuator.RegisterCategoryCommand, Void> {

    private static final Logger LOG =
            LoggerFactory.getLogger(RegisterCategoryCommandActuator.class);

    public record RegisterCategoryCommand(String categoryName, String description) {}

    @Override
    public Void execute(EventService<?> publisher, RegisterCategoryCommand command) {
        Objects.requireNonNull(command.categoryName(), "categoryName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = CategoryAggregate.deriveId(command.categoryName());
        var payload = new CategoryRegisteredEvent(command.categoryName(), command.description());
        AggregateKey key =
                new AggregateKey(aggregateId, CategoryAggregate.AGGREGATE_TYPE, tenantId);
        publisher.publishEvent(
                PublishDomainEventInput.of(
                        tenantId, ConsentEventTypes.CATEGORY_REGISTERED, key, payload));
        LOG.info("Category registered: {}", command.categoryName());
        return null;
    }
}
