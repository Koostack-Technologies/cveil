package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.core.cqrs.CommandActuator;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.metrics.MetricPublisherService;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.aggregates.FieldAggregate;
import com.koostack.operio.core.tenant.consent.events.FieldCategoryAssignedEvent;
import com.koostack.operio.core.tenant.consent.events.FieldSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "assign field category" command.
 *
 * <p>Absorbs orchestration logic formerly split between {@code DefaultTenantService} (query service
 * call to build the snapshot) and {@code JooqConsentAdminCommandService} (event publishing).
 * Instead of calling the query service, the actuator hydrates the {@link FieldAggregate} directly
 * from the event log via {@link EventService#hydrateAggregate}, builds the snapshot, and publishes
 * the domain event.
 */
public class AssignFieldCategoryCommandActuator
        implements CommandActuator<
                AssignFieldCategoryCommandActuator.AssignFieldCategoryCommand, Void> {

    private final EventService<?> eventService;

    public AssignFieldCategoryCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(AssignFieldCategoryCommandActuator.class);

    /**
     * Command input for assigning a category to a field.
     *
     * @param fieldName the field to assign the category to
     * @param entityName the entity owning the field (may be null for default entity)
     * @param categoryName the category to assign
     * @param categoryDescription optional description of the category
     */
    public record AssignFieldCategoryCommand(
            String fieldName, String entityName, String categoryName, String categoryDescription) {}

    @Override
    public Void execute(AssignFieldCategoryCommand command) {
        Objects.requireNonNull(command.fieldName(), "fieldName must not be null");
        Objects.requireNonNull(command.categoryName(), "categoryName must not be null");

        String tenantId = TenantContext.require();
        String normalized = FieldAggregate.normalizeEntityName(command.entityName());
        UUID aggregateId = FieldAggregate.deriveId(normalized, command.fieldName());

        // Hydrate current field state from event log (replaces query service call)
        AggregateKey key = new AggregateKey(aggregateId, FieldAggregate.AGGREGATE_TYPE, tenantId);
        FieldAggregate current = eventPublisher().hydrateAggregate(key, FieldAggregate.class);

        // Build updated category list
        List<String> newCategories = new ArrayList<>();
        if (current != null) {
            current.categories().forEach(c -> newCategories.add(c.categoryName()));
        }
        if (!newCategories.contains(command.categoryName())) {
            newCategories.add(command.categoryName());
        }

        // Build snapshot for projections
        var snapshot =
                new FieldSnapshot(
                        command.fieldName(),
                        normalized,
                        current != null ? current.description() : null,
                        List.copyOf(newCategories));

        // Publish domain event
        var payload =
                new FieldCategoryAssignedEvent(
                        normalized,
                        command.fieldName(),
                        command.categoryName(),
                        command.categoryDescription(),
                        snapshot);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.FIELD_CATEGORY_ASSIGNED, key, payload));

        LOG.info(
                "Field category assigned: field={}, entity={}, category={}",
                command.fieldName(),
                normalized,
                command.categoryName());
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
