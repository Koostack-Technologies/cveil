package com.koostack.operio.core.tenant.consent.aggregates;

import com.koostack.operio.core.models.AggregateRoot;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Aggregate representing a data category (e.g. "PII", "financial").
 * Keyed by category name.
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
    public String aggregateType() { return AGGREGATE_TYPE; }

    public static UUID deriveId(String categoryName) {
        return UUID.nameUUIDFromBytes(
            ("consent-category:" + categoryName).getBytes(StandardCharsets.UTF_8)
        );
    }
}
