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

/**
 * Aggregate representing a consent purpose with its regulation links and policies. Keyed by purpose
 * name.
 */
public record PurposeAggregate(
        String tenantId,
        UUID aggregateId,
        String purposeName,
        String description,
        List<RegulationLink> regulationLinks,
        List<PolicyEntry> policies,
        Long version)
        implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-purpose";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String purposeName) {
        return UUID.nameUUIDFromBytes(
                ("consent-purpose:" + purposeName).getBytes(StandardCharsets.UTF_8));
    }

    public static PurposeAggregate create(String tenantId, String purposeName, String description) {
        return new PurposeAggregate(
                tenantId,
                deriveId(purposeName),
                purposeName,
                description,
                List.of(),
                List.of(),
                0L);
    }

    public static PurposeAggregate apply(
            PurposeAggregate current, String eventType, JsonNode payloadNode, ObjectMapper mapper) {
        return switch (eventType) {
            case ConsentEventTypes.PURPOSE_REGISTERED -> {
                var e = mapper.convertValue(payloadNode, PurposeRegisteredEvent.class);
                if (current == null) {
                    yield PurposeAggregate.create(null, e.purposeName(), e.description());
                }
                yield current.withDescription(e.description());
            }
            case ConsentEventTypes.PURPOSE_UPDATED -> {
                var e = mapper.convertValue(payloadNode, PurposeUpdatedEvent.class);
                if (current == null) {
                    yield PurposeAggregate.create(null, e.purposeName(), e.description());
                }
                yield current.withDescription(e.description());
            }
            case ConsentEventTypes.PURPOSE_REGULATION_LINKED -> {
                var e = mapper.convertValue(payloadNode, PurposeRegulationLinkedEvent.class);
                if (current == null) {
                    current = PurposeAggregate.create(null, e.purposeName(), null);
                }
                yield current.withRegulationLink(
                        e.regulationName(), e.lawfulBasis(), e.onGranted(), e.onDenied());
            }
            case ConsentEventTypes.PURPOSE_REGULATION_UNLINKED -> {
                var e = mapper.convertValue(payloadNode, PurposeRegulationUnlinkedEvent.class);
                if (current == null) {
                    yield current; // nothing to unlink from
                }
                yield current.withoutRegulationLink(e.regulationName());
            }
            case ConsentEventTypes.PURPOSE_POLICY_ADDED -> {
                var e = mapper.convertValue(payloadNode, PurposePolicyAddedEvent.class);
                if (current == null) {
                    current = PurposeAggregate.create(null, e.purposeName(), null);
                }
                yield current.withPolicy(
                        e.policyType(), e.fieldName(), e.entityName(), e.categoryName());
            }
            case ConsentEventTypes.PURPOSE_POLICY_REMOVED -> {
                var e = mapper.convertValue(payloadNode, PurposePolicyRemovedEvent.class);
                if (current == null) {
                    yield current; // nothing to remove from
                }
                yield current.withoutPolicy(
                        e.policyType(), e.fieldName(), e.entityName(), e.categoryName());
            }
            case ConsentEventTypes.PURPOSE_REMOVED -> current;
            default -> throw new IllegalArgumentException(
                    "Unknown event type for PurposeAggregate: " + eventType);
        };
    }

    public PurposeAggregate withDescription(String newDescription) {
        return new PurposeAggregate(
                tenantId,
                aggregateId,
                purposeName,
                newDescription,
                regulationLinks,
                policies,
                version);
    }

    public PurposeAggregate withRegulationLink(
            String regulationName, String lawfulBasis, String onGranted, String onDenied) {
        var updated = new ArrayList<>(regulationLinks);
        updated.removeIf(l -> l.regulationName().equals(regulationName));
        updated.add(new RegulationLink(regulationName, lawfulBasis, onGranted, onDenied));
        return new PurposeAggregate(
                tenantId,
                aggregateId,
                purposeName,
                description,
                List.copyOf(updated),
                policies,
                version);
    }

    public PurposeAggregate withoutRegulationLink(String regulationName) {
        var updated = new ArrayList<>(regulationLinks);
        updated.removeIf(l -> l.regulationName().equals(regulationName));
        return new PurposeAggregate(
                tenantId,
                aggregateId,
                purposeName,
                description,
                List.copyOf(updated),
                policies,
                version);
    }

    public PurposeAggregate withPolicy(
            String policyType, String fieldName, String entityName, String categoryName) {
        var updated = new ArrayList<>(policies);
        updated.removeIf(p -> p.matchesTarget(policyType, fieldName, entityName, categoryName));
        updated.add(new PolicyEntry(policyType, fieldName, entityName, categoryName));
        return new PurposeAggregate(
                tenantId,
                aggregateId,
                purposeName,
                description,
                regulationLinks,
                List.copyOf(updated),
                version);
    }

    public PurposeAggregate withoutPolicy(
            String policyType, String fieldName, String entityName, String categoryName) {
        var updated = new ArrayList<>(policies);
        updated.removeIf(p -> p.matchesTarget(policyType, fieldName, entityName, categoryName));
        return new PurposeAggregate(
                tenantId,
                aggregateId,
                purposeName,
                description,
                regulationLinks,
                List.copyOf(updated),
                version);
    }

    public record RegulationLink(
            String regulationName, String lawfulBasis, String onGranted, String onDenied) {}

    public record PolicyEntry(
            String policyType, String fieldName, String entityName, String categoryName) {
        public boolean matchesTarget(
                String policyType, String fieldName, String entityName, String categoryName) {
            return java.util.Objects.equals(this.policyType, policyType)
                    && java.util.Objects.equals(this.fieldName, fieldName)
                    && java.util.Objects.equals(this.entityName, entityName)
                    && java.util.Objects.equals(this.categoryName, categoryName);
        }
    }
}
