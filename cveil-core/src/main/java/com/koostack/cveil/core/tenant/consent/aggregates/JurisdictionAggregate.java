package com.koostack.cveil.core.tenant.consent.aggregates;

import com.koostack.cveil.core.models.AggregateRoot;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Aggregate representing a jurisdiction (e.g. "EU", "California"). Keyed by jurisdiction name. */
public record JurisdictionAggregate(
        String tenantId,
        UUID aggregateId,
        String jurisdictionName,
        String description,
        Long version)
        implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-jurisdiction";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String jurisdictionName) {
        return UUID.nameUUIDFromBytes(
                ("consent-jurisdiction:" + jurisdictionName).getBytes(StandardCharsets.UTF_8));
    }
}
