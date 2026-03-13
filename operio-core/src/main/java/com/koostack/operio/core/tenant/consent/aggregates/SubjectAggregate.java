package com.koostack.operio.core.tenant.consent.aggregates;

import com.koostack.operio.core.models.AggregateRoot;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Aggregate representing a data subject (e.g. a user, customer, or account). Keyed by subject name.
 * The per-subject DSAR encryption key is managed separately in the {@code subject_dsar} table.
 */
public record SubjectAggregate(
        String tenantId, UUID aggregateId, String subjectName, String description, Long version)
        implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "consent-subject-entity";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    public static UUID deriveId(String subjectName) {
        return UUID.nameUUIDFromBytes(
                ("consent-subject-entity:" + subjectName).getBytes(StandardCharsets.UTF_8));
    }
}
