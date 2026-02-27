package com.koostack.operio.core.tenant.consent.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.events.PolicyGrantedEvent;
import com.koostack.operio.core.tenant.consent.events.PolicyRevokedEvent;
import com.koostack.operio.core.models.AggregateRoot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate representing all consent policies for a single data subject.
 *
 * <p>Keyed by subject ID — each subject has exactly one aggregate instance
 * containing their full consent state. Mutations produce a new immutable
 * record that is published to the event store.
 */
public record ConsentPolicyAggregate(
    String tenantId,
    UUID aggregateId,
    String subjectId,
    List<PolicyEntry> policies,
    Long version
) implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-policy";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String subjectId) {
        return UUID.nameUUIDFromBytes(
            ("consent-policy:" + subjectId).getBytes(StandardCharsets.UTF_8)
        );
    }

    public static ConsentPolicyAggregate create(String tenantId, String subjectId) {
        return new ConsentPolicyAggregate(
            tenantId, deriveId(subjectId), subjectId, List.of(), 0L
        );
    }

    /**
     * Applies a semantic domain event to rebuild aggregate state.
     */
    public static ConsentPolicyAggregate apply(
        ConsentPolicyAggregate current,
        String eventType,
        JsonNode payloadNode,
        ObjectMapper mapper
    ) {
        return switch (eventType) {
            case ConsentEventTypes.POLICY_GRANTED -> {
                var e = mapper.convertValue(payloadNode, PolicyGrantedEvent.class);
                var entry = new PolicyEntry(
                    e.purposeName(), e.fieldName(), e.entityName(),
                    e.category(), e.granted(), e.expiresAt()
                );
                yield current.withPolicy(entry);
            }
            case ConsentEventTypes.POLICY_REVOKED -> {
                var e = mapper.convertValue(payloadNode, PolicyRevokedEvent.class);
                var entry = new PolicyEntry(
                    e.purposeName(), e.fieldName(), e.entityName(),
                    e.category(), e.granted(), e.expiresAt()
                );
                yield current.withPolicy(entry);
            }
            case ConsentEventTypes.POLICY_ALL_REVOKED -> current.withAllRevoked();
            case ConsentEventTypes.POLICY_AUDIT_DELETED -> current;
            default -> throw new IllegalArgumentException(
                "Unknown event type for ConsentPolicyAggregate: " + eventType
            );
        };
    }

    /**
     * Returns a new aggregate with the given policy entry upserted (matching
     * entries by scope are replaced).
     */
    public ConsentPolicyAggregate withPolicy(PolicyEntry entry) {
        var updated = new ArrayList<>(policies);
        updated.removeIf(entry::matchesScope);
        updated.add(entry);
        return new ConsentPolicyAggregate(
            tenantId, aggregateId, subjectId, List.copyOf(updated), version
        );
    }

    /** Returns a new aggregate with all policies cleared. */
    public ConsentPolicyAggregate withAllRevoked() {
        return new ConsentPolicyAggregate(
            tenantId, aggregateId, subjectId, List.of(), version
        );
    }

    /**
     * A single consent policy entry within the aggregate.
     */
    public record PolicyEntry(
        String purposeName,
        String fieldName,
        String entityName,
        String category,
        boolean granted,
        Instant expiresAt
    ) {
        /** Two entries match scope if they share the same purpose + field/category target. */
        public boolean matchesScope(PolicyEntry other) {
            return Objects.equals(purposeName, other.purposeName)
                && Objects.equals(fieldName, other.fieldName)
                && Objects.equals(entityName, other.entityName)
                && Objects.equals(category, other.category);
        }
    }
}
