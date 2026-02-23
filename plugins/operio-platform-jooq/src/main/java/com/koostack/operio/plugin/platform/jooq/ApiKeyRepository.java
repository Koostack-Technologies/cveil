package com.koostack.operio.plugin.platform.jooq;

import com.koostack.operio.plugin.platform.jooq.generated.tables.daos.ApiKeyDao;
import com.koostack.operio.plugin.platform.jooq.generated.tables.pojos.ApiKey;

import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.koostack.operio.plugin.platform.jooq.generated.Tables.API_KEY;

/**
 * Repository for the {@code api_key} table — API key CRUD operations.
 *
 * <p>Wraps the generated {@link ApiKeyDao} for standard CRUD and adds
 * domain-specific lookups: by hash (hot-path token verification), by
 * tenant (listing), and by prefix (revocation).
 *
 * <p>This repository targets the <strong>shared platform schema</strong>
 * (never tenant-routed). The {@link org.jooq.DSLContext} passed in must
 * be created with {@link com.koostack.operio.plugin.jooq.common.JooqContextFactory#createContext}
 * (plain, non-tenant-aware).
 *
 * <h2>Security Note</h2>
 * <p>Raw API keys are never stored. Only the SHA-256 hash ({@code key_hash})
 * and a display prefix ({@code key_prefix}, first 8 characters) are persisted.
 * The raw key is returned exactly once at creation time by
 * {@link JooqPlatformAdminService#createApiKey}.
 */
public class ApiKeyRepository {

    private final DSLContext dsl;
    private final ApiKeyDao dao;

    public ApiKeyRepository(DSLContext dsl) {
        this.dsl = dsl;
        this.dao = new ApiKeyDao(dsl.configuration());
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public ApiKeyDao dao() {
        return dao;
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Looks up an API key by its SHA-256 hash. This is the hot-path lookup
     * used by {@link ApiKeyTokenVerifier#verifyToken} on every authenticated
     * request.
     *
     * @param keyHash the SHA-256 hex digest of the raw API key
     * @return the matching API key row, or empty if none exists
     */
    public Optional<ApiKey> findByKeyHash(String keyHash) {
        return dsl.selectFrom(API_KEY)
                  .where(API_KEY.KEY_HASH.eq(keyHash))
                  .fetchOptionalInto(ApiKey.class);
    }

    /**
     * Returns all API keys belonging to a tenant, ordered by creation time
     * descending (newest first).
     *
     * @param tenantId the owning tenant
     * @return list of API key rows for the tenant, possibly empty
     */
    public List<ApiKey> findByTenant(String tenantId) {
        return dsl.selectFrom(API_KEY)
                  .where(API_KEY.TENANT_ID.eq(tenantId))
                  .orderBy(API_KEY.CREATED_AT.desc())
                  .fetchInto(ApiKey.class);
    }

    /**
     * Looks up an API key by its display prefix (first 8 characters of the
     * raw key). Used for revocation by the platform admin.
     *
     * @param keyPrefix the display prefix to search for
     * @return the matching API key row, or empty if none exists
     */
    public Optional<ApiKey> findByPrefix(String keyPrefix) {
        return dsl.selectFrom(API_KEY)
                  .where(API_KEY.KEY_PREFIX.eq(keyPrefix))
                  .fetchOptionalInto(ApiKey.class);
    }

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    /**
     * Inserts a new API key row.
     *
     * @param keyHash   the SHA-256 hex digest of the raw API key
     * @param keyPrefix the first 8 characters of the raw key (for display
     *                  and revocation)
     * @param tenantId  the owning tenant
     * @param subject   the subject (user/service account) this key represents
     * @param roles     comma-separated roles string (may be {@code null})
     * @param expiresAt optional expiration timestamp (may be {@code null}
     *                  for non-expiring keys)
     * @param actor     the identity performing the operation (may be {@code null})
     */
    public void insert(
        String keyHash,
        String keyPrefix,
        String tenantId,
        String subject,
        String roles,
        LocalDateTime expiresAt,
        String actor
    ) {
        LocalDateTime now = LocalDateTime.now();

        dsl.insertInto(API_KEY)
           .set(API_KEY.KEY_HASH, keyHash)
           .set(API_KEY.KEY_PREFIX, keyPrefix)
           .set(API_KEY.TENANT_ID, tenantId)
           .set(API_KEY.SUBJECT, subject)
           .set(API_KEY.ROLES, roles)
           .set(API_KEY.STATUS, "active")
           .set(API_KEY.EXPIRES_AT, expiresAt)
           .set(API_KEY.CREATED_AT, now)
           .set(API_KEY.CREATED_BY, actor)
           .set(API_KEY.UPDATED_AT, now)
           .set(API_KEY.UPDATED_BY, actor)
           .execute();
    }

    /**
     * Updates the status of an existing API key (e.g. {@code "active"},
     * {@code "revoked"}).
     *
     * @param apiKeyId the database ID of the key to update
     * @param status   the new status
     * @param actor    the identity performing the operation (may be {@code null})
     * @return the number of rows affected (0 or 1)
     */
    public int updateStatus(Long apiKeyId, String status, String actor) {
        return dsl.update(API_KEY)
                  .set(API_KEY.STATUS, status)
                  .set(API_KEY.UPDATED_AT, LocalDateTime.now())
                  .set(API_KEY.UPDATED_BY, actor)
                  .where(API_KEY.API_KEY_ID.eq(apiKeyId))
                  .execute();
    }

    /**
     * Bulk-deletes all API keys belonging to a tenant. Called during tenant
     * teardown by {@link JooqPlatformAdminService#deleteTenant}.
     *
     * @param tenantId the tenant whose keys to delete
     * @return the number of rows deleted
     */
    public int deleteByTenant(String tenantId) {
        return dsl.deleteFrom(API_KEY)
                  .where(API_KEY.TENANT_ID.eq(tenantId))
                  .execute();
    }
}
