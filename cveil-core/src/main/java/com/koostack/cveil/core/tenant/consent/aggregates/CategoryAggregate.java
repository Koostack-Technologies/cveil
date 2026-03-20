package com.koostack.cveil.core.tenant.consent.aggregates;

import com.koostack.cveil.core.models.AggregateRoot;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Aggregate representing a data category (e.g. "PII", "financial"). Keyed by category name. */
public record CategoryAggregate(
        String tenantId, UUID aggregateId, String categoryName, String description, Long version)
        implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-category";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String tenantId, String categoryName) {
        return UUID.nameUUIDFromBytes(
                ("consent-category:" + tenantId + ":" + categoryName)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
