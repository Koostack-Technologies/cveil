package com.koostack.operio.core.tenant.consent.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.events.PurposeRegisteredEvent;
import com.koostack.operio.core.tenant.consent.events.PurposeRegulationLinkedEvent;
import com.koostack.operio.core.tenant.consent.events.PurposeRegulationUnlinkedEvent;
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

    /**
     * Applies a semantic domain event to rebuild aggregate state.
     */
    public static PurposeAggregate apply(
        PurposeAggregate current,
        String eventType,
        JsonNode payloadNode,
        ObjectMapper mapper
    ) {
        return switch (eventType) {
            case ConsentEventTypes.PURPOSE_REGISTERED -> {
                var e = mapper.convertValue(payloadNode, PurposeRegisteredEvent.class);
                yield current.withDescription(e.description());
            }
            case ConsentEventTypes.PURPOSE_REGULATION_LINKED -> {
                var e = mapper.convertValue(payloadNode, PurposeRegulationLinkedEvent.class);
                yield current.withRegulationLink(e.regulationName(), e.legalBasis());
            }
            case ConsentEventTypes.PURPOSE_REGULATION_UNLINKED -> {
                var e = mapper.convertValue(payloadNode, PurposeRegulationUnlinkedEvent.class);
                yield current.withoutRegulationLink(e.regulationName());
            }
            case ConsentEventTypes.PURPOSE_DELETED -> current;
            default -> throw new IllegalArgumentException(
                "Unknown event type for PurposeAggregate: " + eventType
            );
        };
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

    /** Returns a new aggregate with the named regulation link removed. */
    public PurposeAggregate withoutRegulationLink(String regulationName) {
        var updated = new ArrayList<>(regulationLinks);
        updated.removeIf(
            l -> l.regulationName().equals(regulationName)
        );
        return new PurposeAggregate(
            tenantId, aggregateId, purposeName, description,
            List.copyOf(updated), version
        );
    }

    public record RegulationLink(String regulationName, String legalBasis) {}
}
