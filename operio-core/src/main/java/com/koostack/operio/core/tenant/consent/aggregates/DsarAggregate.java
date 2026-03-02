package com.koostack.operio.core.tenant.consent.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.operio.core.tenant.consent.ConsentEventTypes;
import com.koostack.operio.core.tenant.consent.events.SubjectAuditDeletedEvent;
import com.koostack.operio.core.models.AggregateRoot;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Aggregate for DSAR (Data Subject Access Request) audit operations.
 * Keyed by subject ID.
 */
public record DsarAggregate(
    String tenantId,
    UUID aggregateId,
    String subjectId,
    String lastDeletionRequestId,
    Long version
) implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-dsar";

    @Override
    public String aggregateType() { return AGGREGATE_TYPE; }

    public static UUID deriveId(String subjectId) {
        return UUID.nameUUIDFromBytes(
            ("consent-dsar:" + subjectId).getBytes(StandardCharsets.UTF_8)
        );
    }

    public static DsarAggregate create(String tenantId, String subjectId) {
        return new DsarAggregate(
            tenantId, deriveId(subjectId), subjectId, null, 0L
        );
    }

    public static DsarAggregate apply(
        DsarAggregate current, String eventType,
        JsonNode payloadNode, ObjectMapper mapper
    ) {
        return switch (eventType) {
            case ConsentEventTypes.SUBJECT_AUDIT_DELETED -> {
                var e = mapper.convertValue(payloadNode, SubjectAuditDeletedEvent.class);
                yield current.withDeletionRequest(e.deletionRequestId());
            }
            default -> throw new IllegalArgumentException(
                "Unknown event type for DsarAggregate: " + eventType
            );
        };
    }

    public DsarAggregate withDeletionRequest(String deletionRequestId) {
        return new DsarAggregate(
            tenantId, aggregateId, subjectId, deletionRequestId, version
        );
    }
}
