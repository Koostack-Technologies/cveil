package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.FieldAggregate;
import com.koostack.cveil.core.tenant.consent.events.FieldCategoryUnassignedEvent;
import com.koostack.cveil.core.tenant.consent.events.FieldSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "unassign field category" command.
 *
 * <p>Hydrates the current {@link FieldAggregate} from the event log to build the post-mutation
 * {@link FieldSnapshot} (with the target category removed), then publishes the event.
 */
public class UnassignFieldCategoryCommandActuator
        implements CommandActuator<
                UnassignFieldCategoryCommandActuator.UnassignFieldCategoryCommand, Void> {

    private final EventService<?> eventService;

    public UnassignFieldCategoryCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(UnassignFieldCategoryCommandActuator.class);

    public record UnassignFieldCategoryCommand(
            String fieldName, String entityName, String categoryName) {}

    @Override
    public Void execute(UnassignFieldCategoryCommand command) {
        Objects.requireNonNull(command.fieldName(), "fieldName must not be null");
        Objects.requireNonNull(command.categoryName(), "categoryName must not be null");

        String tenantId = TenantContext.require();
        String normalized = FieldAggregate.normalizeEntityName(command.entityName());
        UUID aggregateId = FieldAggregate.deriveId(normalized, command.fieldName());
        AggregateKey key = new AggregateKey(aggregateId, FieldAggregate.AGGREGATE_TYPE, tenantId);

        // Hydrate from event log
        FieldAggregate current = eventPublisher().hydrateAggregate(key, FieldAggregate.class);

        // Build category list with the target removed
        List<String> newCategories = new ArrayList<>();
        if (current != null) {
            current.categories().stream()
                    .map(FieldAggregate.CategoryEntry::categoryName)
                    .filter(name -> !name.equals(command.categoryName()))
                    .forEach(newCategories::add);
        }
        var snapshot =
                new FieldSnapshot(
                        command.fieldName(),
                        normalized,
                        current != null ? current.description() : null,
                        List.copyOf(newCategories));

        var payload =
                new FieldCategoryUnassignedEvent(
                        normalized, command.fieldName(), command.categoryName(), snapshot);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId,
                                ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED,
                                key,
                                payload));

        LOG.info(
                "Field category unassigned: field={}, entity={}, category={}",
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
