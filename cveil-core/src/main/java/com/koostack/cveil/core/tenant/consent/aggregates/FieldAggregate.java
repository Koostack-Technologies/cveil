package com.koostack.cveil.core.tenant.consent.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.models.AggregateRoot;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.events.*;
import com.koostack.cveil.core.util.FieldNameNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate representing a consent field with its owning entity and categories. Keyed by
 * entity_name + field_name.
 */
public record FieldAggregate(
        String tenantId,
        UUID aggregateId,
        String fieldName,
        String description,
        String entityName,
        String entityDescription,
        List<CategoryEntry> categories,
        Long version)
        implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-field";
    public static final String DEFAULT_ENTITY = "__default__";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static String normalizeEntityName(String entityName) {
        if (entityName == null || entityName.isBlank()) return DEFAULT_ENTITY;
        String normalized = FieldNameNormalizer.toSnakeCase(entityName);
        return (normalized != null && !normalized.isBlank()) ? normalized : DEFAULT_ENTITY;
    }

    /** Normalizes a field name to canonical snake_case. */
    public static String normalizeFieldName(String fieldName) {
        if (fieldName == null) return null;
        String normalized = FieldNameNormalizer.toSnakeCase(fieldName);
        return normalized != null ? normalized : fieldName;
    }

    public static UUID deriveId(String entityName, String fieldName) {
        String key = normalizeEntityName(entityName) + ":" + normalizeFieldName(fieldName);
        return UUID.nameUUIDFromBytes(("consent-field:" + key).getBytes(StandardCharsets.UTF_8));
    }

    public static FieldAggregate create(
            String tenantId,
            String entityName,
            String fieldName,
            String description,
            String entityDescription) {
        String normalized = normalizeEntityName(entityName);
        return new FieldAggregate(
                tenantId,
                deriveId(normalized, fieldName),
                fieldName,
                description,
                normalized,
                entityDescription,
                List.of(),
                0L);
    }

    public static FieldAggregate apply(
            FieldAggregate current, String eventType, JsonNode payloadNode, ObjectMapper mapper) {
        return switch (eventType) {
            case ConsentEventTypes.FIELD_REGISTERED -> {
                var e = mapper.convertValue(payloadNode, FieldRegisteredEvent.class);
                if (current == null) {
                    yield create(
                            null,
                            e.entityName(),
                            e.fieldName(),
                            e.description(),
                            e.entityDescription());
                }
                yield current.withDetails(e.description(), e.entityDescription());
            }
            case ConsentEventTypes.FIELD_UPDATED -> {
                var e = mapper.convertValue(payloadNode, FieldUpdatedEvent.class);
                yield current.withDescription(e.description());
            }
            case ConsentEventTypes.FIELD_CATEGORY_ASSIGNED -> {
                var e = mapper.convertValue(payloadNode, FieldCategoryAssignedEvent.class);
                yield current.withCategory(e.categoryName(), e.categoryDescription());
            }
            case ConsentEventTypes.FIELD_CATEGORY_UNASSIGNED -> {
                var e = mapper.convertValue(payloadNode, FieldCategoryUnassignedEvent.class);
                yield current.withoutCategory(e.categoryName());
            }
            case ConsentEventTypes.FIELD_REMOVED -> current;
            default -> throw new IllegalArgumentException(
                    "Unknown event type for FieldAggregate: " + eventType);
        };
    }

    public FieldAggregate withDetails(String newDescription, String newEntityDescription) {
        return new FieldAggregate(
                tenantId,
                aggregateId,
                fieldName,
                newDescription,
                entityName,
                newEntityDescription != null ? newEntityDescription : entityDescription,
                categories,
                version);
    }

    public FieldAggregate withDescription(String newDescription) {
        return new FieldAggregate(
                tenantId,
                aggregateId,
                fieldName,
                newDescription,
                entityName,
                entityDescription,
                categories,
                version);
    }

    public FieldAggregate withCategory(String categoryName, String categoryDescription) {
        var updated = new ArrayList<>(categories);
        updated.removeIf(c -> c.categoryName().equals(categoryName));
        updated.add(new CategoryEntry(categoryName, categoryDescription));
        return new FieldAggregate(
                tenantId,
                aggregateId,
                fieldName,
                description,
                entityName,
                entityDescription,
                List.copyOf(updated),
                version);
    }

    public FieldAggregate withoutCategory(String categoryName) {
        var updated = new ArrayList<>(categories);
        updated.removeIf(c -> c.categoryName().equals(categoryName));
        return new FieldAggregate(
                tenantId,
                aggregateId,
                fieldName,
                description,
                entityName,
                entityDescription,
                List.copyOf(updated),
                version);
    }

    public record CategoryEntry(String categoryName, String categoryDescription) {}
}
