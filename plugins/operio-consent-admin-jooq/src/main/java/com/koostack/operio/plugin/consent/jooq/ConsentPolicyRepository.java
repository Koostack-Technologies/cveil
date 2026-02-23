package com.koostack.operio.plugin.consent.jooq;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.CONSENT_POLICY;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.ConsentPolicyDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentPolicy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * Repository for the {@code consent_policy} aggregate root.
 *
 * <p>Wraps the generated {@link ConsentPolicyDao} for standard CRUD and adds
 * domain-specific operations — primarily upserts keyed on the
 * {@code (subject_id, field_id, purpose_id)} and
 * {@code (subject_id, category_id, purpose_id)} unique constraints.
 *
 * <p>Every consent decision is now scoped to a specific purpose via its
 * relational {@code purpose_id} (foreign key to the {@code purpose} table).
 * This replaces the earlier string-based {@code purpose_name} column and
 * aligns the relational admin schema with the Redis decision layer's
 * {@code ConsentScope(category, fieldName, purposeName)} model — the
 * resolution from {@code purposeName} to {@code purposeId} is performed
 * upstream by {@link JooqConsentAdminService}.
 */
public class ConsentPolicyRepository {

    private final DSLContext dsl;
    private final ConsentPolicyDao dao;

    public ConsentPolicyRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new ConsentPolicyDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public ConsentPolicyDao dao() {
        return dao;
    }

    // -----------------------------------------------------------------------
    // Upserts
    // -----------------------------------------------------------------------

    /**
     * Upserts a field-level consent policy with no expiration.
     *
     * <p>Convenience overload that delegates to
     * {@link #upsertByField(String, Long, Long, boolean, LocalDateTime)}
     * with a {@code null} expiration.
     *
     * @param subjectId the subject whose consent is being recorded
     * @param fieldId   the field the consent decision applies to
     * @param purposeId the purpose this consent is scoped to
     * @param granted   {@code true} to grant, {@code false} to revoke
     */
    public void upsertByField(
        String subjectId,
        Long fieldId,
        Long purposeId,
        boolean granted
    ) {
        upsertByField(
            subjectId,
            fieldId,
            purposeId,
            granted,
            (LocalDateTime) null
        );
    }

    /**
     * Upserts a field-level consent policy with an {@link Instant} expiration.
     *
     * <p>Convenience overload that converts the {@code Instant} to a
     * {@code LocalDateTime} in UTC before delegating.
     *
     * @param subjectId the subject whose consent is being recorded
     * @param fieldId   the field the consent decision applies to
     * @param purposeId the purpose this consent is scoped to
     * @param granted   {@code true} to grant, {@code false} to revoke
     * @param expiresAt when this consent expires, or {@code null} for no
     *                  expiration
     */
    public void upsertByField(
        String subjectId,
        Long fieldId,
        Long purposeId,
        boolean granted,
        Instant expiresAt
    ) {
        upsertByField(
            subjectId,
            fieldId,
            purposeId,
            granted,
            expiresAt != null
                ? LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
                : null
        );
    }

    /**
     * Upserts a field-level consent policy.
     *
     * <p>If no row exists for the given {@code (subjectId, fieldId,
     * purposeId)} triple one is inserted; otherwise the existing row is
     * updated in place.
     *
     * <p>When {@code granted} is {@code true} the {@code granted_at} timestamp
     * is set and any previous revocation is cleared. When {@code false} the
     * {@code revoked_at} timestamp is set and the grant flag is flipped.
     *
     * @param subjectId the subject whose consent is being recorded
     * @param fieldId   the field the consent decision applies to
     * @param purposeId the purpose this consent is scoped to
     * @param granted   {@code true} to grant, {@code false} to revoke
     * @param expiresAt when this consent expires, or {@code null} for no
     *                  expiration
     */
    public void upsertByField(
        String subjectId,
        Long fieldId,
        Long purposeId,
        boolean granted,
        LocalDateTime expiresAt
    ) {
        LocalDateTime now = LocalDateTime.now();

        dsl
            .insertInto(CONSENT_POLICY)
            .set(CONSENT_POLICY.SUBJECT_ID, subjectId)
            .set(CONSENT_POLICY.FIELD_ID, fieldId)
            .set(CONSENT_POLICY.PURPOSE_ID, purposeId)
            .set(CONSENT_POLICY.GRANTED, granted)
            .set(CONSENT_POLICY.EXPIRES_AT, expiresAt)
            .set(CONSENT_POLICY.GRANTED_AT, granted ? now : null)
            .set(CONSENT_POLICY.REVOKED_AT, granted ? null : now)
            .set(CONSENT_POLICY.CREATED_AT, now)
            .set(CONSENT_POLICY.UPDATED_AT, now)
            .onConflict(
                CONSENT_POLICY.SUBJECT_ID,
                CONSENT_POLICY.FIELD_ID,
                CONSENT_POLICY.PURPOSE_ID
            )
            .doUpdate()
            .set(CONSENT_POLICY.GRANTED, granted)
            .set(CONSENT_POLICY.EXPIRES_AT, expiresAt)
            .set(CONSENT_POLICY.GRANTED_AT, granted ? now : null)
            .set(CONSENT_POLICY.REVOKED_AT, granted ? null : now)
            .set(CONSENT_POLICY.UPDATED_AT, now)
            .execute();
    }

    /**
     * Upserts a category-level consent policy with no expiration.
     *
     * <p>Convenience overload that delegates to
     * {@link #upsertByCategory(String, Long, Long, boolean, LocalDateTime)}
     * with a {@code null} expiration.
     *
     * @param subjectId  the subject whose consent is being recorded
     * @param categoryId the category the consent decision applies to
     * @param purposeId  the purpose this consent is scoped to
     * @param granted    {@code true} to grant, {@code false} to revoke
     */
    public void upsertByCategory(
        String subjectId,
        Long categoryId,
        Long purposeId,
        boolean granted
    ) {
        upsertByCategory(
            subjectId,
            categoryId,
            purposeId,
            granted,
            (LocalDateTime) null
        );
    }

    /**
     * Upserts a category-level consent policy with an {@link Instant}
     * expiration.
     *
     * <p>Convenience overload that converts the {@code Instant} to a
     * {@code LocalDateTime} in UTC before delegating.
     *
     * @param subjectId  the subject whose consent is being recorded
     * @param categoryId the category the consent decision applies to
     * @param purposeId  the purpose this consent is scoped to
     * @param granted    {@code true} to grant, {@code false} to revoke
     * @param expiresAt  when this consent expires, or {@code null} for no
     *                   expiration
     */
    public void upsertByCategory(
        String subjectId,
        Long categoryId,
        Long purposeId,
        boolean granted,
        Instant expiresAt
    ) {
        upsertByCategory(
            subjectId,
            categoryId,
            purposeId,
            granted,
            expiresAt != null
                ? LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
                : null
        );
    }

    /**
     * Upserts a category-level consent policy.
     *
     * <p>Behaves identically to {@link #upsertByField} but targets the
     * {@code (subject_id, category_id, purpose_id)} unique constraint
     * instead.
     *
     * @param subjectId  the subject whose consent is being recorded
     * @param categoryId the category the consent decision applies to
     * @param purposeId  the purpose this consent is scoped to
     * @param granted    {@code true} to grant, {@code false} to revoke
     * @param expiresAt  when this consent expires, or {@code null} for no
     *                   expiration
     */
    public void upsertByCategory(
        String subjectId,
        Long categoryId,
        Long purposeId,
        boolean granted,
        LocalDateTime expiresAt
    ) {
        LocalDateTime now = LocalDateTime.now();

        dsl
            .insertInto(CONSENT_POLICY)
            .set(CONSENT_POLICY.SUBJECT_ID, subjectId)
            .set(CONSENT_POLICY.CATEGORY_ID, categoryId)
            .set(CONSENT_POLICY.PURPOSE_ID, purposeId)
            .set(CONSENT_POLICY.GRANTED, granted)
            .set(CONSENT_POLICY.EXPIRES_AT, expiresAt)
            .set(CONSENT_POLICY.GRANTED_AT, granted ? now : null)
            .set(CONSENT_POLICY.REVOKED_AT, granted ? null : now)
            .set(CONSENT_POLICY.CREATED_AT, now)
            .set(CONSENT_POLICY.UPDATED_AT, now)
            .onConflict(
                CONSENT_POLICY.SUBJECT_ID,
                CONSENT_POLICY.CATEGORY_ID,
                CONSENT_POLICY.PURPOSE_ID
            )
            .doUpdate()
            .set(CONSENT_POLICY.GRANTED, granted)
            .set(CONSENT_POLICY.EXPIRES_AT, expiresAt)
            .set(CONSENT_POLICY.GRANTED_AT, granted ? now : null)
            .set(CONSENT_POLICY.REVOKED_AT, granted ? null : now)
            .set(CONSENT_POLICY.UPDATED_AT, now)
            .execute();
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Returns every consent policy for the given subject.
     *
     * @param subjectId the subject identifier
     * @return all matching policies, possibly empty
     */
    public List<ConsentPolicy> findBySubject(String subjectId) {
        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .fetchInto(ConsentPolicy.class);
    }

    /**
     * Returns every consent policy for the given subject scoped to the
     * specified purpose.
     *
     * @param subjectId the subject identifier
     * @param purposeId the purpose to filter by
     * @return all matching policies, possibly empty
     */
    public List<ConsentPolicy> findBySubjectAndPurpose(
        String subjectId,
        Long purposeId
    ) {
        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .and(CONSENT_POLICY.PURPOSE_ID.eq(purposeId))
            .fetchInto(ConsentPolicy.class);
    }

    /**
     * Looks up the field-level policy for a specific subject, field, and
     * purpose.
     *
     * @param subjectId the subject identifier
     * @param fieldId   the field identifier
     * @param purposeId the purpose identifier
     * @return the matching policy, or empty if none exists
     */
    public Optional<ConsentPolicy> findBySubjectAndFieldAndPurpose(
        String subjectId,
        Long fieldId,
        Long purposeId
    ) {
        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .and(CONSENT_POLICY.FIELD_ID.eq(fieldId))
            .and(CONSENT_POLICY.PURPOSE_ID.eq(purposeId))
            .fetchOptionalInto(ConsentPolicy.class);
    }

    /**
     * Looks up the category-level policy for a specific subject, category,
     * and purpose.
     *
     * @param subjectId  the subject identifier
     * @param categoryId the category identifier
     * @param purposeId  the purpose identifier
     * @return the matching policy, or empty if none exists
     */
    public Optional<ConsentPolicy> findBySubjectAndCategoryAndPurpose(
        String subjectId,
        Long categoryId,
        Long purposeId
    ) {
        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .and(CONSENT_POLICY.CATEGORY_ID.eq(categoryId))
            .and(CONSENT_POLICY.PURPOSE_ID.eq(purposeId))
            .fetchOptionalInto(ConsentPolicy.class);
    }

    /**
     * Returns all <em>currently granted</em> policies for a subject —
     * rows where {@code granted = true} and either {@code expires_at} is
     * {@code NULL} or in the future.
     *
     * @param subjectId the subject identifier
     * @return active granted policies, possibly empty
     */
    public List<ConsentPolicy> findActiveGrantsBySubject(String subjectId) {
        LocalDateTime now = LocalDateTime.now();

        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .and(CONSENT_POLICY.GRANTED.isTrue())
            .and(
                CONSENT_POLICY.EXPIRES_AT.isNull().or(
                    CONSENT_POLICY.EXPIRES_AT.gt(now)
                )
            )
            .fetchInto(ConsentPolicy.class);
    }

    /**
     * Returns all <em>currently granted</em> policies for a subject and
     * a specific purpose.
     *
     * @param subjectId the subject identifier
     * @param purposeId the purpose to filter by
     * @return active granted policies for that purpose, possibly empty
     */
    public List<ConsentPolicy> findActiveGrantsBySubjectAndPurpose(
        String subjectId,
        Long purposeId
    ) {
        LocalDateTime now = LocalDateTime.now();

        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .and(CONSENT_POLICY.PURPOSE_ID.eq(purposeId))
            .and(CONSENT_POLICY.GRANTED.isTrue())
            .and(
                CONSENT_POLICY.EXPIRES_AT.isNull().or(
                    CONSENT_POLICY.EXPIRES_AT.gt(now)
                )
            )
            .fetchInto(ConsentPolicy.class);
    }

    // -----------------------------------------------------------------------
    // Bulk queries (reconciliation support)
    // -----------------------------------------------------------------------

    /**
     * Returns every consent policy in the system, regardless of subject,
     * status, or expiration.
     *
     * <p>This method is intended for bulk operations such as cache
     * reconciliation, where the caller needs a complete snapshot of the
     * relational state. For large datasets consider streaming or
     * paginating instead.
     *
     * @return all consent policy rows, possibly empty
     */
    public List<ConsentPolicy> findAll() {
        return dsl.selectFrom(CONSENT_POLICY).fetchInto(ConsentPolicy.class);
    }

    /**
     * Returns every <em>currently active</em> consent policy — rows where
     * {@code granted = true} and either {@code expires_at} is {@code NULL}
     * or in the future.
     *
     * <p>This is the global counterpart of
     * {@link #findActiveGrantsBySubject(String)}: it spans all subjects and
     * is the recommended source-of-truth query for full cache rebuilds,
     * since expired and revoked policies do not need to be materialized
     * into the decision cache.
     *
     * @return all active granted policies, possibly empty
     */
    public List<ConsentPolicy> findAllActive() {
        LocalDateTime now = LocalDateTime.now();

        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.GRANTED.isTrue())
            .and(
                CONSENT_POLICY.EXPIRES_AT.isNull().or(
                    CONSENT_POLICY.EXPIRES_AT.gt(now)
                )
            )
            .fetchInto(ConsentPolicy.class);
    }

    /**
     * Returns every consent policy for the given subject — both granted
     * and revoked, expired and unexpired.
     *
     * <p>Unlike {@link #findActiveGrantsBySubject(String)}, which filters
     * for currently-active grants only, this method returns the complete
     * state for a single subject. It is useful for subject-scoped cache
     * reconciliation where both granted and explicitly-revoked entries
     * should be materialized.
     *
     * @param subjectId the subject identifier
     * @return all policies for the subject, possibly empty
     */
    public List<ConsentPolicy> findAllBySubject(String subjectId) {
        return dsl
            .selectFrom(CONSENT_POLICY)
            .where(CONSENT_POLICY.SUBJECT_ID.eq(subjectId))
            .fetchInto(ConsentPolicy.class);
    }
}
