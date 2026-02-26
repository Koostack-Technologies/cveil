package com.koostack.operio.core.data.consent.aggregates;

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

    /** Returns a new aggregate with an updated description. */
    public CategoryAggregate withDescription(String newDescription) {
        return new CategoryAggregate(
            tenantId, aggregateId, categoryName, newDescription, version
        );
    }
}
