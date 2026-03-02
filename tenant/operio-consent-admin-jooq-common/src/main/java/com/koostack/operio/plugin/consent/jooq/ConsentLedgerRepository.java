package com.koostack.operio.plugin.consent.jooq;

import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.List;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.*;

/**
 * Repository for the {@code consent_ledger} table — the current consent
 * state for each subject+policy pair.
 *
 * <p>The consent ledger uses upsert semantics (UNIQUE on subject_id+policy_id),
 * so each row represents the current state. The event stream is the
 * historical record.
 */
public class ConsentLedgerRepository {

    private final DSLContext dsl;

    public ConsentLedgerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Returns all consent ledger entries, joined with policy, purpose,
     * field/category, and entity information for reconciliation.
     */
    public List<Record> findAll() {
        return dsl.select(CONSENT_LEDGER.fields())
            .select(POLICY.POLICY_TYPE)
            .select(PURPOSE.PURPOSE_NAME)
            .select(FIELD.FIELD_NAME)
            .select(ENTITY.ENTITY_NAME)
            .select(CATEGORY.CATEGORY_NAME)
            .from(CONSENT_LEDGER)
            .join(POLICY).on(CONSENT_LEDGER.POLICY_ID.eq(POLICY.POLICY_ID))
            .join(PURPOSE).on(POLICY.PURPOSE_ID.eq(PURPOSE.PURPOSE_ID))
            .leftJoin(FIELD).on(POLICY.FIELD_ID.eq(FIELD.FIELD_ID))
            .leftJoin(ENTITY).on(FIELD.ENTITY_ID.eq(ENTITY.ENTITY_ID))
            .leftJoin(CATEGORY).on(POLICY.CATEGORY_ID.eq(CATEGORY.CATEGORY_ID))
            .fetch();
    }

    /**
     * Returns all consent ledger entries for a subject, joined with policy,
     * purpose, field/category, and entity information for aggregate views.
     */
    public List<Record> findBySubject(String subjectId) {
        return dsl.select(CONSENT_LEDGER.fields())
            .select(POLICY.POLICY_TYPE)
            .select(PURPOSE.PURPOSE_NAME)
            .select(FIELD.FIELD_NAME)
            .select(ENTITY.ENTITY_NAME)
            .select(CATEGORY.CATEGORY_NAME)
            .from(CONSENT_LEDGER)
            .join(POLICY).on(CONSENT_LEDGER.POLICY_ID.eq(POLICY.POLICY_ID))
            .join(PURPOSE).on(POLICY.PURPOSE_ID.eq(PURPOSE.PURPOSE_ID))
            .leftJoin(FIELD).on(POLICY.FIELD_ID.eq(FIELD.FIELD_ID))
            .leftJoin(ENTITY).on(FIELD.ENTITY_ID.eq(ENTITY.ENTITY_ID))
            .leftJoin(CATEGORY).on(POLICY.CATEGORY_ID.eq(CATEGORY.CATEGORY_ID))
            .where(CONSENT_LEDGER.SUBJECT_ID.eq(subjectId))
            .fetch();
    }

    /**
     * Returns all GRANT entries that have expired (for sweeper).
     *
     * @param cutoff entries with expires_at at or before this time are considered expired
     */
    public List<Record> findExpiredGrants(java.time.LocalDateTime cutoff) {
        return dsl.select(CONSENT_LEDGER.fields())
            .select(POLICY.POLICY_TYPE)
            .select(PURPOSE.PURPOSE_NAME)
            .select(FIELD.FIELD_NAME)
            .select(ENTITY.ENTITY_NAME)
            .select(CATEGORY.CATEGORY_NAME)
            .from(CONSENT_LEDGER)
            .join(POLICY).on(CONSENT_LEDGER.POLICY_ID.eq(POLICY.POLICY_ID))
            .join(PURPOSE).on(POLICY.PURPOSE_ID.eq(PURPOSE.PURPOSE_ID))
            .leftJoin(FIELD).on(POLICY.FIELD_ID.eq(FIELD.FIELD_ID))
            .leftJoin(ENTITY).on(FIELD.ENTITY_ID.eq(ENTITY.ENTITY_ID))
            .leftJoin(CATEGORY).on(POLICY.CATEGORY_ID.eq(CATEGORY.CATEGORY_ID))
            .where(CONSENT_LEDGER.ACTION.eq(com.koostack.operio.plugin.consent.jooq.generated.enums.ConsentAction.GRANT))
            .and(CONSENT_LEDGER.EXPIRES_AT.isNotNull())
            .and(CONSENT_LEDGER.EXPIRES_AT.le(cutoff))
            .fetch();
    }
}
