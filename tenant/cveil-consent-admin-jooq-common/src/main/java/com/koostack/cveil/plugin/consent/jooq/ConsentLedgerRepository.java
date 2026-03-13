package com.koostack.cveil.plugin.consent.jooq;

import static com.koostack.cveil.plugin.consent.jooq.generated.Tables.*;

import com.koostack.cveil.core.models.TenantContext;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * Repository for the {@code consent_ledger} table — an append-only historical record of all consent
 * actions.
 *
 * <p>Each row is immutable once written. Queries return the latest entry per (subject_id,
 * policy_id) using a {@code ROW_NUMBER()} window function, which is compatible with both PostgreSQL
 * and H2.
 */
public class ConsentLedgerRepository {

    private final DSLContext dsl;

    public ConsentLedgerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Returns the current consent state for all subjects — the most recent ledger entry per
     * (subject_id, policy_id), joined with policy, purpose, field/category, and entity information.
     */
    public List<Record> findAll() {
        var rn =
                DSL.rowNumber()
                        .over(
                                DSL.partitionBy(CONSENT_LEDGER.SUBJECT_ID, CONSENT_LEDGER.POLICY_ID)
                                        .orderBy(CONSENT_LEDGER.CREATED_AT.desc()))
                        .as("rn");

        var latest =
                dsl.select(CONSENT_LEDGER.fields())
                        .select(rn)
                        .from(CONSENT_LEDGER)
                        .where(CONSENT_LEDGER.TENANT_ID.eq(TenantContext.require()))
                        .asTable("ranked");

        return dsl.select(latest.fields(CONSENT_LEDGER.fields()))
                .select(POLICY.POLICY_TYPE)
                .select(PURPOSE.PURPOSE_NAME)
                .select(FIELD.FIELD_NAME)
                .select(ENTITY.ENTITY_NAME)
                .select(CATEGORY.CATEGORY_NAME)
                .from(latest)
                .join(POLICY)
                .on(latest.field(CONSENT_LEDGER.POLICY_ID).eq(POLICY.POLICY_ID))
                .join(PURPOSE)
                .on(POLICY.PURPOSE_ID.eq(PURPOSE.PURPOSE_ID))
                .leftJoin(FIELD)
                .on(POLICY.FIELD_ID.eq(FIELD.FIELD_ID))
                .leftJoin(ENTITY)
                .on(FIELD.ENTITY_ID.eq(ENTITY.ENTITY_ID))
                .leftJoin(CATEGORY)
                .on(POLICY.CATEGORY_ID.eq(CATEGORY.CATEGORY_ID))
                .where(latest.field("rn", Integer.class).eq(1))
                .fetch();
    }

    /**
     * Returns the current consent state for a single subject — the most recent ledger entry per
     * policy_id, joined with policy metadata.
     */
    public List<Record> findBySubject(String subjectId) {
        var rn =
                DSL.rowNumber()
                        .over(
                                DSL.partitionBy(CONSENT_LEDGER.POLICY_ID)
                                        .orderBy(CONSENT_LEDGER.CREATED_AT.desc()))
                        .as("rn");

        var latest =
                dsl.select(CONSENT_LEDGER.fields())
                        .select(rn)
                        .from(CONSENT_LEDGER)
                        .where(CONSENT_LEDGER.SUBJECT_ID.eq(subjectId))
                        .and(CONSENT_LEDGER.TENANT_ID.eq(TenantContext.require()))
                        .asTable("ranked");

        return dsl.select(latest.fields(CONSENT_LEDGER.fields()))
                .select(POLICY.POLICY_TYPE)
                .select(PURPOSE.PURPOSE_NAME)
                .select(FIELD.FIELD_NAME)
                .select(ENTITY.ENTITY_NAME)
                .select(CATEGORY.CATEGORY_NAME)
                .from(latest)
                .join(POLICY)
                .on(latest.field(CONSENT_LEDGER.POLICY_ID).eq(POLICY.POLICY_ID))
                .join(PURPOSE)
                .on(POLICY.PURPOSE_ID.eq(PURPOSE.PURPOSE_ID))
                .leftJoin(FIELD)
                .on(POLICY.FIELD_ID.eq(FIELD.FIELD_ID))
                .leftJoin(ENTITY)
                .on(FIELD.ENTITY_ID.eq(ENTITY.ENTITY_ID))
                .leftJoin(CATEGORY)
                .on(POLICY.CATEGORY_ID.eq(CATEGORY.CATEGORY_ID))
                .where(latest.field("rn", Integer.class).eq(1))
                .fetch();
    }

    /**
     * Returns all currently-granted entries that have expired (for sweeper). Only considers the
     * latest entry per (subject_id, policy_id) where the current action is GRANT and expires_at
     * &le; cutoff.
     *
     * @param cutoff entries with expires_at at or before this time are considered expired
     */
    public List<Record> findExpiredGrants(java.time.LocalDateTime cutoff) {
        var rn =
                DSL.rowNumber()
                        .over(
                                DSL.partitionBy(CONSENT_LEDGER.SUBJECT_ID, CONSENT_LEDGER.POLICY_ID)
                                        .orderBy(CONSENT_LEDGER.CREATED_AT.desc()))
                        .as("rn");

        var latest =
                dsl.select(CONSENT_LEDGER.fields())
                        .select(rn)
                        .from(CONSENT_LEDGER)
                        .where(CONSENT_LEDGER.TENANT_ID.eq(TenantContext.require()))
                        .asTable("ranked");

        return dsl.select(latest.fields(CONSENT_LEDGER.fields()))
                .select(POLICY.POLICY_TYPE)
                .select(PURPOSE.PURPOSE_NAME)
                .select(FIELD.FIELD_NAME)
                .select(ENTITY.ENTITY_NAME)
                .select(CATEGORY.CATEGORY_NAME)
                .from(latest)
                .join(POLICY)
                .on(latest.field(CONSENT_LEDGER.POLICY_ID).eq(POLICY.POLICY_ID))
                .join(PURPOSE)
                .on(POLICY.PURPOSE_ID.eq(PURPOSE.PURPOSE_ID))
                .leftJoin(FIELD)
                .on(POLICY.FIELD_ID.eq(FIELD.FIELD_ID))
                .leftJoin(ENTITY)
                .on(FIELD.ENTITY_ID.eq(ENTITY.ENTITY_ID))
                .leftJoin(CATEGORY)
                .on(POLICY.CATEGORY_ID.eq(CATEGORY.CATEGORY_ID))
                .where(latest.field("rn", Integer.class).eq(1))
                .and(
                        latest.field(CONSENT_LEDGER.ACTION)
                                .eq(
                                        com.koostack.cveil.plugin.consent.jooq.generated.enums
                                                .ConsentAction.GRANT))
                .and(latest.field(CONSENT_LEDGER.EXPIRES_AT).isNotNull())
                .and(latest.field(CONSENT_LEDGER.EXPIRES_AT).le(cutoff))
                .fetch();
    }
}
