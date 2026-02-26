package com.koostack.operio.core.data.consent.aggregates;

import com.koostack.operio.core.models.AggregateRoot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate representing a consent purpose (e.g. {@code "marketing"},
 * {@code "analytics"}) with its regulation links.
 *
 * <p>Keyed by purpose name — one aggregate per purpose.
 */
public record PurposeAggregate(
    String tenantId,
    UUID aggregateId,
    String purposeName,
    String description,
    List<RegulationLink> regulationLinks,
    Long version
) implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-purpose";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String purposeName) {
        return UUID.nameUUIDFromBytes(
            ("consent-purpose:" + purposeName).getBytes(StandardCharsets.UTF_8)
        );
    }

    public static PurposeAggregate create(
        String tenantId, String purposeName, String description
    ) {
        return new PurposeAggregate(
            tenantId, deriveId(purposeName), purposeName, description,
            List.of(), 0L
        );
    }

    /** Returns a new aggregate with an updated description. */
    public PurposeAggregate withDescription(String newDescription) {
        return new PurposeAggregate(
            tenantId, aggregateId, purposeName, newDescription,
            regulationLinks, version
        );
    }

    /** Returns a new aggregate with a regulation link added (idempotent). */
    public PurposeAggregate withRegulationLink(
        String regulationName, String legalBasis
    ) {
        var updated = new ArrayList<>(regulationLinks);
        updated.removeIf(
            l -> l.regulationName().equals(regulationName)
        );
        updated.add(new RegulationLink(regulationName, legalBasis));
        return new PurposeAggregate(
            tenantId, aggregateId, purposeName, description,
            List.copyOf(updated), version
        );
    }

    public record RegulationLink(String regulationName, String legalBasis) {}
}
