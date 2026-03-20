package com.koostack.cveil.core.tenant.consent.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.models.AggregateRoot;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.events.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Aggregate representing a regulation with its jurisdictions. Keyed by regulation name. */
public record RegulationAggregate(
        String tenantId,
        UUID aggregateId,
        String regulationName,
        String description,
        List<JurisdictionEntry> jurisdictions,
        Long version)
        implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-regulation";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String tenantId, String regulationName) {
        return UUID.nameUUIDFromBytes(
                ("consent-regulation:" + tenantId + ":" + regulationName)
                        .getBytes(StandardCharsets.UTF_8));
    }

    public static RegulationAggregate create(
            String tenantId, String regulationName, String description) {
        return new RegulationAggregate(
                tenantId,
                deriveId(tenantId, regulationName),
                regulationName,
                description,
                List.of(),
                0L);
    }

    public static RegulationAggregate apply(
            RegulationAggregate current,
            String eventType,
            JsonNode payloadNode,
            ObjectMapper mapper) {
        return switch (eventType) {
            case ConsentEventTypes.REGULATION_REGISTERED -> {
                var e = mapper.convertValue(payloadNode, RegulationRegisteredEvent.class);
                if (current == null) {
                    yield RegulationAggregate.create(null, e.regulationName(), e.description());
                }
                yield current.withDescription(e.description());
            }
            case ConsentEventTypes.REGULATION_UPDATED -> {
                var e = mapper.convertValue(payloadNode, RegulationUpdatedEvent.class);
                yield current.withDescription(e.description());
            }
            case ConsentEventTypes.REGULATION_JURISDICTION_ADDED -> {
                var e = mapper.convertValue(payloadNode, RegulationJurisdictionAddedEvent.class);
                yield current.withJurisdiction(e.jurisdictionName(), e.description());
            }
            case ConsentEventTypes.REGULATION_JURISDICTION_REMOVED -> {
                var e = mapper.convertValue(payloadNode, RegulationJurisdictionRemovedEvent.class);
                yield current.withoutJurisdiction(e.jurisdictionName());
            }
            case ConsentEventTypes.REGULATION_REMOVED -> current;
            default -> throw new IllegalArgumentException(
                    "Unknown event type for RegulationAggregate: " + eventType);
        };
    }

    public RegulationAggregate withDescription(String newDescription) {
        return new RegulationAggregate(
                tenantId, aggregateId, regulationName, newDescription, jurisdictions, version);
    }

    public RegulationAggregate withJurisdiction(String jurisdictionName, String description) {
        var updated = new ArrayList<>(jurisdictions);
        updated.removeIf(j -> j.jurisdictionName().equals(jurisdictionName));
        updated.add(new JurisdictionEntry(jurisdictionName, description));
        return new RegulationAggregate(
                tenantId,
                aggregateId,
                regulationName,
                this.description,
                List.copyOf(updated),
                version);
    }

    public RegulationAggregate withoutJurisdiction(String jurisdictionName) {
        var updated = new ArrayList<>(jurisdictions);
        updated.removeIf(j -> j.jurisdictionName().equals(jurisdictionName));
        return new RegulationAggregate(
                tenantId, aggregateId, regulationName, description, List.copyOf(updated), version);
    }

    public record JurisdictionEntry(String jurisdictionName, String description) {}
}
