package com.koostack.operio.core.data.consent.aggregates;

import com.koostack.operio.core.models.AggregateRoot;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Aggregate representing a consent regulation (e.g. {@code "GDPR"},
 * {@code "CCPA"}).
 *
 * <p>Keyed by regulation name — one aggregate per regulation.
 */
public record RegulationAggregate(
    String tenantId,
    UUID aggregateId,
    String regulationName,
    String fullName,
    String jurisdiction,
    String description,
    String url,
    Long version
) implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-regulation";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String regulationName) {
        return UUID.nameUUIDFromBytes(
            ("consent-regulation:" + regulationName)
                .getBytes(StandardCharsets.UTF_8)
        );
    }

    public static RegulationAggregate create(
        String tenantId,
        String regulationName,
        String fullName,
        String jurisdiction,
        String description,
        String url
    ) {
        return new RegulationAggregate(
            tenantId, deriveId(regulationName), regulationName,
            fullName, jurisdiction, description, url, 0L
        );
    }

    /** Returns a new aggregate with updated fields. */
    public RegulationAggregate withDetails(
        String fullName, String jurisdiction,
        String description, String url
    ) {
        return new RegulationAggregate(
            tenantId, aggregateId, regulationName,
            fullName, jurisdiction, description, url, version
        );
    }
}
