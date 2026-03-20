package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.FieldAggregate;
import com.koostack.cveil.core.tenant.consent.events.FieldCategoryAssignedEvent;
import com.koostack.cveil.core.tenant.consent.events.FieldSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "assign field category" command.
 *
 * <p>Absorbs orchestration logic formerly split between the legacy service layer (query service
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
        String normalizedEntity = FieldAggregate.normalizeEntityName(command.entityName());
        String normalizedField = FieldAggregate.normalizeFieldName(command.fieldName());
        UUID aggregateId = FieldAggregate.deriveId(tenantId, normalizedEntity, normalizedField);

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
                        normalizedField,
                        normalizedEntity,
                        current != null ? current.description() : null,
                        List.copyOf(newCategories));

        // Publish domain event
        var payload =
                new FieldCategoryAssignedEvent(
                        normalizedEntity,
                        normalizedField,
                        command.categoryName(),
                        command.categoryDescription(),
                        snapshot);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.FIELD_CATEGORY_ASSIGNED, key, payload));

        LOG.info(
                "Field category assigned: field={}, entity={}, category={}",
                normalizedField,
                normalizedEntity,
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
