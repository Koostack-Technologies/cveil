package com.koostack.operio.plugin.consent.jooq;

import com.koostack.operio.plugin.consent.jooq.generated.tables.daos.ConsentAuditLogDao;
import com.koostack.operio.plugin.consent.jooq.generated.tables.pojos.ConsentAuditLog;

import org.jooq.Condition;
import org.jooq.DSLContext;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.koostack.operio.plugin.consent.jooq.generated.Tables.CONSENT_AUDIT_LOG;

/**
 * Repository for the {@code consent_audit_log} table — append-only audit
 * trail for consent events.
 *
 * <p>This table is per-tenant (lives in {@code t_{tenantId}} schemas
 * alongside {@code consent_policy}) and is append-only — rows are never
 * updated or deleted except by DSAR erasure (future). This is what
 * regulators audit.
 *
 * <p>Wraps the generated {@link ConsentAuditLogDao} for standard CRUD and
 * adds domain-specific insert and query methods.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is safe for concurrent use — all state is final and the
 * underlying jOOQ {@link DSLContext} is thread-safe.
 */
public class ConsentAuditLogRepository {

    private final DSLContext dsl;
    private final ConsentAuditLogDao dao;

    public ConsentAuditLogRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new ConsentAuditLogDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public ConsentAuditLogDao dao() {
        return dao;
    }

    // -----------------------------------------------------------------------
    // Insert (append-only)
    // -----------------------------------------------------------------------

    /**
     * Inserts a single audit log entry.
     *
     * <p>This is the primary write path — called from
     * {@code JooqConsentAdminService.upsert()} immediately after the
     * {@code consent_policy} upsert so both writes share the same
     * transactional context.
     *
     * @param subjectId  the data subject identifier
     * @param fieldId    the resolved field ID, or {@code null} for category-level consent
     * @param categoryId the resolved category ID, or {@code null} for field-level consent
     * @param purposeId  the resolved purpose ID
     * @param action     the action performed ({@code "GRANTED"} or {@code "REVOKED"})
     * @param granted    {@code true} for grant, {@code false} for revoke
     * @param expiresAt  when the consent expires, or {@code null} for no expiration
     * @param actor      the identity performing the action (e.g. the admin user or the subject)
     * @param ipAddress  the originating IP address, or {@code null}
     * @param userAgent  the originating User-Agent header, or {@code null}
     * @param metadata   additional JSON metadata, or {@code null}
     */
    public void insert(
        String subjectId,
        Long fieldId,
        Long categoryId,
        Long purposeId,
        String action,
        boolean granted,
        Instant expiresAt,
        String actor,
        String ipAddress,
        String userAgent,
        String metadata
    ) {
        LocalDateTime expiresAtLdt = expiresAt != null
            ? LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
            : null;

        dsl.insertInto(CONSENT_AUDIT_LOG)
           .set(CONSENT_AUDIT_LOG.SUBJECT_ID, subjectId)
           .set(CONSENT_AUDIT_LOG.FIELD_ID, fieldId)
           .set(CONSENT_AUDIT_LOG.CATEGORY_ID, categoryId)
           .set(CONSENT_AUDIT_LOG.PURPOSE_ID, purposeId)
           .set(CONSENT_AUDIT_LOG.ACTION, action)
           .set(CONSENT_AUDIT_LOG.GRANTED, granted)
           .set(CONSENT_AUDIT_LOG.EXPIRES_AT, expiresAtLdt)
           .set(CONSENT_AUDIT_LOG.ACTOR, actor)
           .set(CONSENT_AUDIT_LOG.IP_ADDRESS, ipAddress)
           .set(CONSENT_AUDIT_LOG.USER_AGENT, userAgent)
           .set(CONSENT_AUDIT_LOG.METADATA, metadata)
           // created_at has a DEFAULT CURRENT_TIMESTAMP — let the DB set it
           .execute();
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Returns the full audit history for the given subject, ordered by
     * {@code created_at DESC} (most recent first).
     *
     * <p>This is the primary read path for DSAR exports and admin
     * dashboards.
     *
     * @param subjectId the data subject identifier
     * @return every audit log entry for the subject, possibly empty
     */
    public List<ConsentAuditLog> findBySubject(String subjectId) {
        return dsl.selectFrom(CONSENT_AUDIT_LOG)
                  .where(CONSENT_AUDIT_LOG.SUBJECT_ID.eq(subjectId))
                  .orderBy(CONSENT_AUDIT_LOG.CREATED_AT.desc())
                  .fetchInto(ConsentAuditLog.class);
    }

    /**
     * Returns the audit history for the given subject filtered by scope
     * (field, category, and/or purpose), ordered by {@code created_at DESC}.
     *
     * <p>Any of {@code fieldId}, {@code categoryId}, or {@code purposeId}
     * may be {@code null} — only non-null values are included in the WHERE
     * clause. At minimum, {@code subjectId} is always filtered.
     *
     * @param subjectId  the data subject identifier
     * @param fieldId    the field ID filter, or {@code null} to skip
     * @param categoryId the category ID filter, or {@code null} to skip
     * @param purposeId  the purpose ID filter, or {@code null} to skip
     * @return filtered audit log entries, possibly empty
     */
    public List<ConsentAuditLog> findBySubjectAndScope(
        String subjectId,
        Long fieldId,
        Long categoryId,
        Long purposeId
    ) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(CONSENT_AUDIT_LOG.SUBJECT_ID.eq(subjectId));

        if (fieldId != null) {
            conditions.add(CONSENT_AUDIT_LOG.FIELD_ID.eq(fieldId));
        }
        if (categoryId != null) {
            conditions.add(CONSENT_AUDIT_LOG.CATEGORY_ID.eq(categoryId));
        }
        if (purposeId != null) {
            conditions.add(CONSENT_AUDIT_LOG.PURPOSE_ID.eq(purposeId));
        }

        return dsl.selectFrom(CONSENT_AUDIT_LOG)
                  .where(conditions)
                  .orderBy(CONSENT_AUDIT_LOG.CREATED_AT.desc())
                  .fetchInto(ConsentAuditLog.class);
    }

    // -----------------------------------------------------------------------
    // DSAR erasure (future)
    // -----------------------------------------------------------------------

    /**
     * Deletes all audit log entries for the given subject.
     *
     * <p><strong>Warning:</strong> This is a destructive operation intended
     * only for DSAR (Data Subject Access Request) erasure compliance. It
     * should not be used for normal operations. The audit log is designed
     * to be append-only under normal circumstances.
     *
     * @param subjectId the data subject identifier
     * @return the number of rows deleted
     */
    public int deleteBySubject(String subjectId) {
        return dsl.deleteFrom(CONSENT_AUDIT_LOG)
                  .where(CONSENT_AUDIT_LOG.SUBJECT_ID.eq(subjectId))
                  .execute();
    }
}
