package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.CategoryAggregate;
import com.koostack.cveil.core.tenant.consent.events.CategoryRemovedEvent;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoveCategoryCommandActuator
        implements CommandActuator<RemoveCategoryCommandActuator.RemoveCategoryCommand, Void> {

    private final EventService<?> eventService;

    public RemoveCategoryCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG = LoggerFactory.getLogger(RemoveCategoryCommandActuator.class);

    public record RemoveCategoryCommand(String categoryName) {}

    @Override
    public Void execute(RemoveCategoryCommand command) {
        Objects.requireNonNull(command.categoryName(), "categoryName must not be null");
        String tenantId = TenantContext.require();
        UUID aggregateId = CategoryAggregate.deriveId(tenantId, command.categoryName());
        var payload = new CategoryRemovedEvent(command.categoryName());
        AggregateKey key =
                new AggregateKey(aggregateId, CategoryAggregate.AGGREGATE_TYPE, tenantId);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.CATEGORY_REMOVED, key, payload));
        LOG.info("Category removed: {}", command.categoryName());
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
