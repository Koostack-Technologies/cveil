package com.koostack.operio.core.data.consent.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.data.consent.ConsentEventTypes;
import com.koostack.operio.core.data.consent.events.CategoryRegisteredEvent;
import com.koostack.operio.core.models.AggregateRoot;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Aggregate representing a consent category (e.g. {@code "PII"},
 * {@code "HEALTH"}).
 *
 * <p>Keyed by category name — one aggregate per category.
 */
public record CategoryAggregate(
    String tenantId,
    UUID aggregateId,
    String categoryName,
    String description,
    Long version
) implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-category";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String categoryName) {
        return UUID.nameUUIDFromBytes(
            ("consent-category:" + categoryName).getBytes(StandardCharsets.UTF_8)
        );
    }

    public static CategoryAggregate create(
        String tenantId, String categoryName, String description
    ) {
        return new CategoryAggregate(
            tenantId, deriveId(categoryName), categoryName, description, 0L
        );
    }

    /**
     * Applies a semantic domain event to rebuild aggregate state.
     */
    public static CategoryAggregate apply(
        CategoryAggregate current,
        String eventType,
        JsonNode payloadNode,
        ObjectMapper mapper
    ) {
        return switch (eventType) {
            case ConsentEventTypes.CATEGORY_REGISTERED -> {
                var e = mapper.convertValue(payloadNode, CategoryRegisteredEvent.class);
                yield current.withDescription(e.description());
            }
            case ConsentEventTypes.CATEGORY_DELETED -> current;
            default -> throw new IllegalArgumentException(
                "Unknown event type for CategoryAggregate: " + eventType
            );
        };
    }

    /** Returns a new aggregate with an updated description. */
    public CategoryAggregate withDescription(String newDescription) {
        return new CategoryAggregate(
            tenantId, aggregateId, categoryName, newDescription, version
        );
    }
}
