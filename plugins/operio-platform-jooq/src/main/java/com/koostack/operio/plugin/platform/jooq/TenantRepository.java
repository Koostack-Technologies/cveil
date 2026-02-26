package com.koostack.operio.plugin.platform.jooq;

import static com.koostack.operio.plugin.platform.jooq.generated.Tables.TENANT;

import com.koostack.operio.plugin.platform.jooq.crypto.FieldEncryptor;
import com.koostack.operio.plugin.platform.jooq.generated.tables.daos.TenantDao;
import com.koostack.operio.plugin.platform.jooq.generated.tables.pojos.Tenant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repository for the {@code tenant} table — tenant lifecycle management.
 *
 * <p>Wraps the generated {@link TenantDao} for standard CRUD and adds
 * domain-specific operations: status transitions, plan changes with
 * rate-limit recalculation, and tenant lookup by ID.
 *
 * <p>This repository targets the <strong>shared platform schema</strong>
 * (never tenant-routed). The {@link org.jooq.DSLContext} passed in must
 * be created with {@link com.koostack.operio.plugin.jooq.common.JooqContextFactory#createContext}
 * (plain, non-tenant-aware).
 */
public class TenantRepository {

    private static final Logger log = LoggerFactory.getLogger(
        TenantRepository.class
    );

    private final DSLContext dsl;
    private final TenantDao dao;
    private final String encryptionKey;

    /**
     * Default rate limits per plan. Keys are lowercase plan names.
     * Values are {@code [evalLimitMonthly, subjectLimit, burstLimitPerMin]}.
     */
    private static final Map<String, long[]> PLAN_LIMITS = Map.of(
        "free",
        new long[] { 10_000L, 1_000L, 100 },
        "pro",
        new long[] { 500_000L, 50_000L, 500 },
        "enterprise",
        new long[] { 5_000_000L, 500_000L, 2000 }
    );

    /**
     * Creates a repository with encryption support.
     *
     * @param dsl           the platform {@link DSLContext}
     * @param encryptionKey the AES-256-GCM encryption key for the
     *                      {@code redis_acl_password} column, or
     *                      {@code null} to disable encryption
     */
    public TenantRepository(DSLContext dsl, String encryptionKey) {
        this.dsl = dsl;
        this.dao = new TenantDao(dsl.configuration());
        this.encryptionKey = encryptionKey;
    }

    /**
     * Backward-compatible constructor — no encryption.
     *
     * @param dsl the platform {@link DSLContext}
     */
    public TenantRepository(DSLContext dsl) {
        this(dsl, null);
    }

    /** Exposes the generated DAO for standard CRUD when needed. */
    public TenantDao dao() {
        return dao;
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /**
     * Looks up a tenant by its unique identifier.
     *
     * @param tenantId the tenant identifier
     * @return the matching tenant, or empty if none exists
     */
    public Optional<Tenant> findById(String tenantId) {
        return dsl
            .selectFrom(TENANT)
            .where(TENANT.TENANT_ID.eq(tenantId))
            .fetchOptionalInto(Tenant.class)
            .map(this::decryptTenantPassword);
    }

    /**
     * Returns all tenants, ordered by tenant ID.
     *
     * @return every tenant row, possibly empty
     */
    public List<Tenant> findAll() {
        return dsl
            .selectFrom(TENANT)
            .orderBy(TENANT.TENANT_ID)
            .fetchInto(Tenant.class)
            .stream()
            .map(this::decryptTenantPassword)
            .toList();
    }

    // -----------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------

    /**
     * Inserts a new tenant row with rate limits derived from the given plan.
     *
     * @param tenantId          unique tenant identifier
     * @param displayName       human-readable tenant name (may be {@code null})
     * @param plan              subscription plan (e.g. {@code "free"}, {@code "pro"},
     *                          {@code "enterprise"})
     * @param schemaName        the dedicated Postgres schema name (e.g. {@code "t_acme"})
     * @param redisAclUser      the Redis ACL username provisioned for this tenant
     *                          (may be {@code null} if Redis is not configured)
     * @param redisAclPassword  the plaintext Redis ACL password for this tenant,
     *                          used at runtime to create per-tenant JedisPooled
     *                          connections that enforce Redis-level key isolation
     *                          (may be {@code null} if Redis is not configured)
     * @param redisAclPassHash  the SHA-256 hex hash of the Redis ACL password
     *                          for audit/verification purposes
     *                          (may be {@code null} if Redis is not configured)
     * @param actor             the identity performing the operation (may be {@code null})
     */
    public void insert(
        String tenantId,
        String displayName,
        String plan,
        String schemaName,
        String redisAclUser,
        String redisAclPassword,
        String redisAclPassHash,
        String actor
    ) {
        LocalDateTime now = LocalDateTime.now();
        long[] limits = limitsForPlan(plan);

        String encryptedPassword = FieldEncryptor.encryptIfKeyPresent(
            redisAclPassword,
            encryptionKey
        );

        dsl
            .insertInto(TENANT)
            .set(TENANT.TENANT_ID, tenantId)
            .set(TENANT.DISPLAY_NAME, displayName)
            .set(TENANT.PLAN, plan)
            .set(TENANT.STATUS, "active")
            .set(TENANT.SCHEMA_NAME, schemaName)
            .set(TENANT.REDIS_ACL_USER, redisAclUser)
            .set(TENANT.REDIS_ACL_PASSWORD, encryptedPassword)
            .set(TENANT.REDIS_ACL_PASS_HASH, redisAclPassHash)
            .set(TENANT.EVAL_LIMIT_MONTHLY, limits[0])
            .set(TENANT.SUBJECT_LIMIT, limits[1])
            .set(TENANT.BURST_LIMIT_PER_MIN, (int) limits[2])
            .set(TENANT.CREATED_AT, now)
            .set(TENANT.CREATED_BY, actor)
            .set(TENANT.UPDATED_AT, now)
            .set(TENANT.UPDATED_BY, actor)
            .execute();
    }

    /**
     * Updates the status of an existing tenant (e.g. {@code "active"},
     * {@code "suspended"}, {@code "deactivated"}).
     *
     * @param tenantId the tenant to update
     * @param status   the new status
     * @param actor    the identity performing the operation (may be {@code null})
     * @return the number of rows affected (0 or 1)
     */
    public int updateStatus(String tenantId, String status, String actor) {
        return dsl
            .update(TENANT)
            .set(TENANT.STATUS, status)
            .set(TENANT.UPDATED_AT, LocalDateTime.now())
            .set(TENANT.UPDATED_BY, actor)
            .where(TENANT.TENANT_ID.eq(tenantId))
            .execute();
    }

    /**
     * Changes the plan for an existing tenant and recalculates rate limit
     * columns based on the new plan.
     *
     * @param tenantId the tenant to update
     * @param plan     the new plan (e.g. {@code "free"}, {@code "pro"},
     *                 {@code "enterprise"})
     * @param actor    the identity performing the operation (may be {@code null})
     * @return the number of rows affected (0 or 1)
     */
    public int updatePlan(String tenantId, String plan, String actor) {
        long[] limits = limitsForPlan(plan);

        return dsl
            .update(TENANT)
            .set(TENANT.PLAN, plan)
            .set(TENANT.EVAL_LIMIT_MONTHLY, limits[0])
            .set(TENANT.SUBJECT_LIMIT, limits[1])
            .set(TENANT.BURST_LIMIT_PER_MIN, (int) limits[2])
            .set(TENANT.UPDATED_AT, LocalDateTime.now())
            .set(TENANT.UPDATED_BY, actor)
            .where(TENANT.TENANT_ID.eq(tenantId))
            .execute();
    }

    /**
     * Updates the Redis ACL credentials for an existing tenant.
     *
     * <p>Used by {@link TenantManager#reprovisionRedisAcl(String)} to
     * backfill or rotate per-tenant Redis ACL credentials. After calling
     * this method, the {@code TenantAwareJedisProvider} will pick up the
     * new credentials on the next pool creation (or after the existing
     * pool is evicted).
     *
     * @param tenantId         the tenant to update
     * @param redisAclUser     the Redis ACL username (e.g. {@code "t_acme"})
     * @param redisAclPassword the new plaintext Redis ACL password
     * @param redisAclPassHash the SHA-256 hex hash of the new password
     * @param actor            the identity performing the operation (may be {@code null})
     * @return the number of rows affected (0 or 1)
     */
    public int updateRedisAclCredentials(
        String tenantId,
        String redisAclUser,
        String redisAclPassword,
        String redisAclPassHash,
        String actor
    ) {
        String encryptedPassword = FieldEncryptor.encryptIfKeyPresent(
            redisAclPassword,
            encryptionKey
        );

        return dsl
            .update(TENANT)
            .set(TENANT.REDIS_ACL_USER, redisAclUser)
            .set(TENANT.REDIS_ACL_PASSWORD, encryptedPassword)
            .set(TENANT.REDIS_ACL_PASS_HASH, redisAclPassHash)
            .set(TENANT.UPDATED_AT, LocalDateTime.now())
            .set(TENANT.UPDATED_BY, actor)
            .where(TENANT.TENANT_ID.eq(tenantId))
            .execute();
    }

    /**
     * Hard-deletes a tenant row. Only call after the tenant's Postgres
     * schema and Redis ACL user have been torn down by {@link TenantManager}.
     *
     * @param tenantId the tenant to delete
     * @return the number of rows deleted (0 or 1)
     */
    public int delete(String tenantId) {
        return dsl
            .deleteFrom(TENANT)
            .where(TENANT.TENANT_ID.eq(tenantId))
            .execute();
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Decrypts the {@code redis_acl_password} field on a tenant POJO.
     *
     * <p>Uses {@link FieldEncryptor#decryptIfKeyPresent} which handles:
     * <ul>
     *   <li>No encryption key → returns the raw value unchanged</li>
     *   <li>Encrypted value → decrypts and returns plaintext</li>
     *   <li>Legacy plaintext value (decryption fails) → returns as-is</li>
     * </ul>
     *
     * @param tenant the tenant POJO from jOOQ
     * @return the same tenant with the password field decrypted
     */
    private Tenant decryptTenantPassword(Tenant tenant) {
        if (tenant == null) {
            return null;
        }
        String raw = tenant.getRedisAclPassword();
        if (raw != null && encryptionKey != null && !encryptionKey.isBlank()) {
            String decrypted = FieldEncryptor.decryptIfKeyPresent(
                raw,
                encryptionKey
            );
            if (!decrypted.equals(raw)) {
                log.debug(
                    "Decrypted redis_acl_password for tenant '{}'",
                    tenant.getTenantId()
                );
            }
            tenant.setRedisAclPassword(decrypted);
        }
        return tenant;
    }

    /**
     * Resolves rate limits for a plan name. Falls back to {@code "free"}
     * limits for unrecognized plans.
     */
    private static long[] limitsForPlan(String plan) {
        return PLAN_LIMITS.getOrDefault(
            plan == null ? "free" : plan.toLowerCase(),
            PLAN_LIMITS.get("free")
        );
    }
}
