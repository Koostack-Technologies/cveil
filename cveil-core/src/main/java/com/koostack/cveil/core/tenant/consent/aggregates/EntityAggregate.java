package com.koostack.cveil.core.tenant.consent.aggregates;

import com.koostack.cveil.core.models.AggregateRoot;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Aggregate representing a data entity (e.g. "users", "orders"). Keyed by entity name. */
public record EntityAggregate(
        String tenantId, UUID aggregateId, String entityName, String description, Long version)
        implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-entity";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String entityName) {
        return UUID.nameUUIDFromBytes(
                ("consent-entity:" + entityName).getBytes(StandardCharsets.UTF_8));
    }
}
