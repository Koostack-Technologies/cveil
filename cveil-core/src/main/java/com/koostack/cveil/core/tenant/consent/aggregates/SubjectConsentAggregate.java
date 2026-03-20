package com.koostack.cveil.core.tenant.consent.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.models.AggregateRoot;
import com.koostack.cveil.core.tenant.consent.ConsentEventTypes;
import com.koostack.cveil.core.tenant.consent.events.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate representing all consent ledger entries for a single data subject. Keyed by subject ID.
 */
public record SubjectConsentAggregate(
        String tenantId,
        UUID aggregateId,
        String subjectId,
        List<LedgerEntry> entries,
        Long version)
        implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-subject";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String tenantId, String subjectId) {
        return UUID.nameUUIDFromBytes(
                ("consent-subject:" + tenantId + ":" + subjectId).getBytes(StandardCharsets.UTF_8));
    }

    public static SubjectConsentAggregate create(String tenantId, String subjectId) {
        return new SubjectConsentAggregate(
                tenantId, deriveId(tenantId, subjectId), subjectId, List.of(), 0L);
    }

    public static SubjectConsentAggregate apply(
            SubjectConsentAggregate current,
            String eventType,
            JsonNode payloadNode,
            ObjectMapper mapper) {
        return switch (eventType) {
            case ConsentEventTypes.CONSENT_GRANTED -> {
                var e = mapper.convertValue(payloadNode, ConsentGrantedEvent.class);
                var base =
                        current != null
                                ? current
                                : SubjectConsentAggregate.create(null, e.subjectId());
                yield base.withEntry(
                        new LedgerEntry(
                                e.policyId(),
                                e.purposeName(),
                                e.policyType(),
                                e.fieldName(),
                                e.entityName(),
                                e.categoryName(),
                                "GRANT",
                                e.actor(),
                                e.ipAddress(),
                                e.userAgent(),
                                e.expiresAt()));
            }
            case ConsentEventTypes.CONSENT_REVOKED -> {
                var e = mapper.convertValue(payloadNode, ConsentRevokedEvent.class);
                yield current.withEntry(
                        new LedgerEntry(
                                e.policyId(),
                                e.purposeName(),
                                e.policyType(),
                                e.fieldName(),
                                e.entityName(),
                                e.categoryName(),
                                "REVOKE",
                                e.actor(),
                                e.ipAddress(),
                                e.userAgent(),
                                null));
            }
            case ConsentEventTypes.CONSENT_EXPIRED -> {
                var e = mapper.convertValue(payloadNode, ConsentExpiredEvent.class);
                yield current.withEntry(
                        new LedgerEntry(
                                e.policyId(),
                                e.purposeName(),
                                e.policyType(),
                                e.fieldName(),
                                e.entityName(),
                                e.categoryName(),
                                "EXPIRE",
                                null,
                                null,
                                null,
                                null));
            }
            case ConsentEventTypes.CONSENT_ALL_REVOKED -> current.withAllRevoked();
            default -> throw new IllegalArgumentException(
                    "Unknown event type for SubjectConsentAggregate: " + eventType);
        };
    }

    /** Upsert a ledger entry by policy_id. */
    public SubjectConsentAggregate withEntry(LedgerEntry entry) {
        var updated = new ArrayList<>(entries);
        updated.removeIf(e -> Objects.equals(e.policyId(), entry.policyId()));
        updated.add(entry);
        return new SubjectConsentAggregate(
                tenantId, aggregateId, subjectId, List.copyOf(updated), version);
    }

    /** Mark all entries as revoked. */
    public SubjectConsentAggregate withAllRevoked() {
        var updated =
                entries.stream()
                        .map(
                                e ->
                                        new LedgerEntry(
                                                e.policyId(),
                                                e.purposeName(),
                                                e.policyType(),
                                                e.fieldName(),
                                                e.entityName(),
                                                e.categoryName(),
                                                "REVOKE",
                                                e.actor(),
                                                e.ipAddress(),
                                                e.userAgent(),
                                                null))
                        .toList();
        return new SubjectConsentAggregate(tenantId, aggregateId, subjectId, updated, version);
    }

    public record LedgerEntry(
            UUID policyId,
            String purposeName,
            String policyType,
            String fieldName,
            String entityName,
            String categoryName,
            String action,
            String actor,
            String ipAddress,
            String userAgent,
            Instant expiresAt) {}
}
