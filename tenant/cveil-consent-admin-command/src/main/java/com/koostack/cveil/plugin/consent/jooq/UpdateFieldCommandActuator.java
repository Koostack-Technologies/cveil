package com.koostack.cveil.plugin.consent.jooq;

import com.koostack.cveil.core.cqrs.CommandActuator;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.aggregates.FieldAggregate;
import com.koostack.cveil.core.tenant.consent.events.FieldSnapshot;
import com.koostack.cveil.core.tenant.consent.events.FieldUpdatedEvent;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Actuator that handles the "update field" command.
 *
 * <p>Hydrates the current {@link FieldAggregate} from the event log to build the {@link
 * FieldSnapshot} (preserving existing categories), then publishes the update event.
 */
public class UpdateFieldCommandActuator
        implements CommandActuator<UpdateFieldCommandActuator.UpdateFieldCommand, Void> {

    private final EventService<?> eventService;

    public UpdateFieldCommandActuator(EventService<?> eventService) {
        this.eventService = eventService;
    }

    private static final Logger LOG = LoggerFactory.getLogger(UpdateFieldCommandActuator.class);

    public record UpdateFieldCommand(String fieldName, String entityName, String description) {}

    @Override
    public Void execute(UpdateFieldCommand command) {
        Objects.requireNonNull(command.fieldName(), "fieldName must not be null");
        String tenantId = TenantContext.require();
        String normalized = FieldAggregate.normalizeEntityName(command.entityName());
        UUID aggregateId = FieldAggregate.deriveId(tenantId, normalized, command.fieldName());
        AggregateKey key = new AggregateKey(aggregateId, FieldAggregate.AGGREGATE_TYPE, tenantId);

        // Hydrate to preserve existing categories in snapshot
        FieldAggregate current = eventPublisher().hydrateAggregate(key, FieldAggregate.class);
        List<String> categoryNames =
                current != null
                        ? current.categories().stream()
                                .map(FieldAggregate.CategoryEntry::categoryName)
                                .toList()
                        : List.of();
        var snapshot =
                new FieldSnapshot(
                        command.fieldName(), normalized, command.description(), categoryNames);

        var payload =
                new FieldUpdatedEvent(
                        normalized, command.fieldName(), command.description(), snapshot);
        eventPublisher()
                .publishEvent(
                        PublishDomainEventInput.of(
                                tenantId, ConsentEventTypes.FIELD_UPDATED, key, payload));
        LOG.info("Field updated: field={}, entity={}", command.fieldName(), normalized);
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
