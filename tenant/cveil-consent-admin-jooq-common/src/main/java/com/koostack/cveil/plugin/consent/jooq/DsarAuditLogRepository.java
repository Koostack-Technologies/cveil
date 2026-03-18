package com.koostack.cveil.plugin.consent.jooq;

import static com.koostack.cveil.plugin.consent.jooq.generated.Tables.DSAR_AUDIT_LOG;

import com.koostack.cveil.core.models.TenantContext;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.daos.DsarAuditLogDao;
import com.koostack.cveil.plugin.consent.jooq.generated.tables.pojos.DsarAuditLog;
import java.util.List;
import org.jooq.DSLContext;

/**
 * Repository for the {@code dsar_audit_log} table — append-only audit trail for Data Subject Access
 * Request (DSAR) erasure operations.
 *
 * <p>This table is per-tenant (lives in {@code t_{tenantId}} schemas alongside {@code
 * consent_policy} and {@code consent_audit_log}) and is append-only — rows are never updated or
 * deleted. It records deletion requests for forensic compliance auditing and regulatory
 * traceability (GDPR Article 17, CCPA §1798.105).
 *
 * <p>Unlike {@code consent_audit_log} (which gets deleted during DSAR erasure), this table is
 * preserved to prove that a deletion was requested and completed.
 *
 * <p>Wraps the generated {@link DsarAuditLogDao} for standard CRUD and adds domain-specific insert
 * and query methods.
 *
 * <h2>Multi-Tenant Isolation</h2>
 *
 * <p>All queries executed through this repository are automatically routed to the correct
 * per-tenant Postgres schema by {@link
 * com.koostack.cveil.plugin.jooq.common.TenantAwareExecuteListener TenantAwareExecuteListener}.
 * When a {@link TenantContext TenantContext} is active, the execute listener issues {@code SET
 * search_path TO t_{tenantId}, public} before each statement, ensuring that:
 *
 * <ul>
 *   <li>Inserts write to the correct tenant's {@code dsar_audit_log} table
 *   <li>Queries only see DSAR entries belonging to the current tenant
 *   <li>No cross-tenant data leakage is possible at the database level
 * </ul>
 *
 * <p>When no {@code TenantContext} is active (single-tenant mode), queries use the default Postgres
 * search path (typically the {@code public} schema), maintaining backward compatibility.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is safe for concurrent use — all state is final and the underlying jOOQ {@link
 * DSLContext} is thread-safe.
 */
public class DsarAuditLogRepository {

    private final DSLContext dsl;
    private final DsarAuditLogDao dao;

    public DsarAuditLogRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new DsarAuditLogDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public DsarAuditLogDao dao() {
        return dao;
    }

    // -----------------------------------------------------------------------
    // Insert (append-only)
    // -----------------------------------------------------------------------

    /**
     * Inserts a single DSAR deletion audit log entry.
     *
     * <p>This is the primary write path for recording DSAR erasure operations. Called after a
     * deletion request completes so that we have a permanent record of what was deleted.
     *
     * <p><strong>Tenant scoping:</strong> When a {@link TenantContext} is active, the insert
     * targets the tenant's schema automatically via {@code TenantAwareExecuteListener}. The caller
     * does not need to specify a tenant ID — it is derived from the scoped context.
     *
     * @param subjectId the data subject identifier
     * @param deletionRequestId unique request ID for this erasure (UUID)
     * @param policiesDeleted number of consent policies deleted
     * @param cachedKeysDeleted number of cached keys deleted from Redis
     * @param auditEntriesDeleted number of audit log entries deleted
     * @param status {@code "SUCCESS"} or {@code "PARTIAL_FAILURE"}
     * @param actor the identity initiating the deletion (e.g. "SYSTEM", user ID)
     * @param ipAddress the originating IP address, or {@code null}
     * @param userAgent the originating User-Agent header, or {@code null}
     * @param metadata additional JSON metadata (error details, etc.), or {@code null}
     */
    public void insert(
            String subjectId,
            String deletionRequestId,
            int policiesDeleted,
            int cachedKeysDeleted,
            int auditEntriesDeleted,
            String status,
            String actor,
            String ipAddress,
            String userAgent,
            String metadata) {
        dsl.insertInto(DSAR_AUDIT_LOG)
                .set(DSAR_AUDIT_LOG.SUBJECT_ID, subjectId)
                .set(DSAR_AUDIT_LOG.DELETION_REQUEST_ID, deletionRequestId)
                .set(DSAR_AUDIT_LOG.POLICIES_DELETED, policiesDeleted)
                .set(DSAR_AUDIT_LOG.CACHED_KEYS_DELETED, cachedKeysDeleted)
                .set(DSAR_AUDIT_LOG.AUDIT_ENTRIES_DELETED, auditEntriesDeleted)
                .set(DSAR_AUDIT_LOG.STATUS, status)
                .set(DSAR_AUDIT_LOG.ACTOR, actor)
                .set(DSAR_AUDIT_LOG.IP_ADDRESS, ipAddress)
                .set(DSAR_AUDIT_LOG.USER_AGENT, userAgent)
                .set(DSAR_AUDIT_LOG.METADATA, metadata)
                // created_at has a DEFAULT CURRENT_TIMESTAMP — let the DB set it
                .execute();
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Returns the full DSAR deletion history for the given subject, ordered by {@code created_at
     * DESC} (most recent first).
     *
     * <p>This is the primary read path for forensic audits and compliance reports.
     *
     * <p><strong>Tenant scoping:</strong> When a {@link TenantContext TenantContext} is active,
     * results are automatically restricted to the current tenant's schema. A subject ID that exists
     * in tenant A's schema will not appear in queries executed under tenant B's context —
     * cross-tenant data leakage is prevented at the database level.
     *
     * @param subjectId the data subject identifier
     * @return every DSAR deletion entry for the subject, possibly empty
     */
    public List<DsarAuditLog> findBySubject(String subjectId) {
        return findBySubject(subjectId, 100, 0);
    }

    /**
     * Returns a paginated DSAR deletion history for the given subject, ordered by {@code created_at
     * DESC} (most recent first).
     *
     * @param subjectId the data subject identifier
     * @param limit maximum number of results to return
     * @param offset number of results to skip
     * @return DSAR deletion entries for the subject within the requested page
     */
    public List<DsarAuditLog> findBySubject(String subjectId, int limit, int offset) {
        return dsl.selectFrom(DSAR_AUDIT_LOG)
                .where(DSAR_AUDIT_LOG.SUBJECT_ID.eq(subjectId))
                .and(DSAR_AUDIT_LOG.TENANT_ID.eq(TenantContext.require()))
                .orderBy(DSAR_AUDIT_LOG.CREATED_AT.desc())
                .limit(limit)
                .offset(offset)
                .fetchInto(DsarAuditLog.class);
    }

    /**
     * Looks up a specific DSAR deletion request by its unique request ID.
     *
     * <p>Useful for receipt verification and forensic traceability.
     *
     * <p><strong>Tenant scoping:</strong> Results are restricted to the current tenant's schema
     * when a {@link TenantContext TenantContext} is active. A deletion request ID from tenant A
     * cannot be looked up under tenant B's context.
     *
     * @param deletionRequestId the unique deletion request ID (UUID)
     * @return the matching DSAR audit log entry, or an empty list if not found
     */
    public List<DsarAuditLog> findByDeletionRequestId(String deletionRequestId) {
        return dsl.selectFrom(DSAR_AUDIT_LOG)
                .where(DSAR_AUDIT_LOG.DELETION_REQUEST_ID.eq(deletionRequestId))
                .and(DSAR_AUDIT_LOG.TENANT_ID.eq(TenantContext.require()))
                .fetchInto(DsarAuditLog.class);
    }

    /**
     * Returns the count of successful DSAR deletions for a subject.
     *
     * <p>Useful for dashboards and reporting.
     *
     * <p><strong>Tenant scoping:</strong> Count is restricted to the current tenant's schema when a
     * {@link TenantContext TenantContext} is active.
     *
     * @param subjectId the data subject identifier
     * @return the number of successful deletions (status = 'SUCCESS')
     */
    public long countSuccessfulDeletions(String subjectId) {
        return dsl.selectCount()
                .from(DSAR_AUDIT_LOG)
                .where(DSAR_AUDIT_LOG.SUBJECT_ID.eq(subjectId))
                .and(DSAR_AUDIT_LOG.STATUS.eq("SUCCESS"))
                .and(DSAR_AUDIT_LOG.TENANT_ID.eq(TenantContext.require()))
                .fetchOne(0, Long.class);
    }
}
