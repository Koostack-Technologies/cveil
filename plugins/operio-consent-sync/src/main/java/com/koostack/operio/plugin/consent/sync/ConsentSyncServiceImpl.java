package com.koostack.operio.plugin.consent.sync;

import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.service.ConsentSyncService;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminService;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository;
import com.koostack.operio.plugin.consent.redis.FieldCategoryMappingRedisRepository;
import com.koostack.operio.plugin.consent.redis.RedisConsentDecisionService;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import java.time.Instant;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dual-write coordinator that keeps the relational consent admin store and
 * the Redis decision-layer cache in sync.
 *
 * <h2>Write Strategy</h2>
 * <ol>
 *   <li>Write to the relational DB via {@link JooqConsentAdminService}
 *       (source of truth).</li>
 *   <li>Sync to Redis via {@link ConsentPolicyRedisRepository} and
 *       {@link FieldCategoryMappingRedisRepository}.</li>
 * </ol>
 *
 * <h2>Error Handling</h2>
 * <p>The DB write is always performed first. If the subsequent Redis write
 * fails, the error is caught and logged — the DB row is <em>never</em>
 * rolled back on cache failure. Redis may be stale until the next
 * successful sync or a reconciliation job corrects the drift.
 *
 * <h2>Category Fan-Out</h2>
 * <p>Category-level writes are materialized (fanned out) into individual
 * field keys at write time. The fan-out respects field-level precedence:
 * existing {@code FIELD}-sourced entries in Redis are never overwritten by
 * a category fan-out.
 *
 * <h2>TTL Handling</h2>
 * <p>When an {@code expiresAt} timestamp is provided, the Redis keys are
 * written with a TTL derived from
 * {@code Duration.between(Instant.now(), expiresAt)}. If the computed TTL
 * is zero or negative, the Redis write is skipped (the entry is already
 * expired). If {@code expiresAt} is {@code null}, no TTL is set.
 *
 * <h2>Auto-Registration</h2>
 * <p>Before each DB write, any entities, fields, and categories referenced
 * by the consent scope are auto-registered in the admin schema using
 * idempotent upsert semantics. This acts as a safety net so that a
 * {@code grantConsent} or {@code revokeConsent} call never fails simply
 * because the caller forgot to register a class with the discovery
 * pipeline. Field-to-category links are also synced to the Redis mapping
 * repository so that subsequent category-level fan-out writes discover
 * the field.
 *
 * @see ConsentSyncService
 * @see JooqConsentAdminService
 * @see ConsentPolicyRedisRepository
 */
public class ConsentSyncServiceImpl
    implements ConsentSyncService<ConsentSyncServiceConfig>, Operio.AppNameAware
{

    private static final Logger log = Logger.getLogger(
        ConsentSyncServiceImpl.class.getName()
    );

    private JooqConsentAdminService jooqAdmin;
    private ConsentPolicyRedisRepository redisConsentRepo;
    private FieldCategoryMappingRedisRepository redisMappingRepo;
    private boolean autoRegisterMetadata = true;
    private boolean configured;

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "consent-sync";
    }

    @Override
    public int getPriority() {
        return 300;
    }

    @Override
    public Class<ConsentSyncServiceConfig> getConfigurationType() {
        return ConsentSyncServiceConfig.class;
    }

    @Override
    public void configure(ConsentSyncServiceConfig config) {
        // Initialize the jOOQ admin service with its own config
        this.jooqAdmin = new JooqConsentAdminService();
        this.jooqAdmin.configure(config.jooq());

        // Initialize Redis repositories via a temporary decision service
        // to reuse its connection setup and repository construction
        RedisConsentDecisionService redisDecision =
            new RedisConsentDecisionService();
        redisDecision.configure(config.redis());
        this.redisConsentRepo = redisDecision.consentPolicyRepository();
        this.redisMappingRepo = redisDecision.fieldCategoryMappingRepository();

        this.autoRegisterMetadata = config.autoRegisterMetadata();
        this.configured = true;
    }

    /**
     * Configures this sync service with a {@link JedisProvider} for
     * per-tenant Redis connection resolution.
     *
     * <p>This is the recommended configuration path for multi-tenant
     * deployments. The provided {@link JedisProvider} (typically a
     * {@link com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider})
     * is shared between this sync service and the
     * {@link RedisConsentDecisionService}, ensuring that all Redis
     * operations — both writes (sync) and reads (evaluation) —
     * authenticate as the current tenant's Redis ACL user.
     *
     * <p>This enforces <strong>server-side</strong> key isolation at the
     * Redis ACL level: each tenant's ACL user is restricted to keys
     * matching {@code {appName}:{tenantId}:*}, so even a bug in
     * application-level key-prefix logic cannot leak data between tenants.
     *
     * @param jooqConfig          configuration for the jOOQ consent admin layer
     * @param jedisProvider       the per-tenant Jedis provider
     * @param autoRegisterMetadata whether to auto-register entities/fields/categories
     */
    public void configure(
        JooqConsentAdminServiceConfig jooqConfig,
        JedisProvider jedisProvider,
        boolean autoRegisterMetadata
    ) {
        // Initialize the jOOQ admin service
        this.jooqAdmin = new JooqConsentAdminService();
        this.jooqAdmin.configure(jooqConfig);

        // Initialize Redis repositories with the tenant-aware provider
        this.redisConsentRepo = new ConsentPolicyRedisRepository(jedisProvider);
        this.redisMappingRepo = new FieldCategoryMappingRedisRepository(
            jedisProvider
        );

        this.autoRegisterMetadata = autoRegisterMetadata;
        this.configured = true;
    }

    // -----------------------------------------------------------------------
    // AppNameAware
    // -----------------------------------------------------------------------

    /**
     * Propagates the application name to the underlying Redis repositories
     * so that all keys are prefixed with the given name.
     *
     * @param appName the application name, never {@code null}
     */
    @Override
    public void setAppName(String appName) {
        if (redisConsentRepo != null) {
            redisConsentRepo.setAppName(appName);
        }
        if (redisMappingRepo != null) {
            redisMappingRepo.setAppName(appName);
        }
    }

    // -----------------------------------------------------------------------
    // ConsentSyncService
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Writes a grant to the relational DB, then syncs the grant to Redis.
     *
     * <p>Before the DB write, any entities, fields, and categories
     * referenced by the scope are auto-registered in the admin schema
     * (idempotent upsert). This ensures consent grants never fail simply
     * because the caller forgot to register metadata via discovery.
     */
    @Override
    public void syncGrant(
        String subject,
        ConsentScope scope,
        Instant expiresAt
    ) {
        ensureConfigured();

        // Step 0: auto-register metadata so resolveFieldId / resolveCategoryId
        // won't fail on unknown names (skipped when autoRegisterMetadata is false)
        if (autoRegisterMetadata) {
            ensureMetadataRegistered(scope);
        }

        // Step 1: DB write (source of truth)
        jooqAdmin.grantConsent(subject, scope, expiresAt);

        // Step 2: Redis sync (best-effort)
        syncToRedis(subject, scope, true, expiresAt);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes a revocation to the relational DB, then syncs the revocation
     * to Redis.
     *
     * <p>Before the DB write, any entities, fields, and categories
     * referenced by the scope are auto-registered in the admin schema
     * (idempotent upsert). This ensures consent revocations never fail
     * simply because the caller forgot to register metadata via discovery.
     */
    @Override
    public void syncRevoke(
        String subject,
        ConsentScope scope,
        Instant expiresAt
    ) {
        ensureConfigured();

        // Step 0: auto-register metadata so resolveFieldId / resolveCategoryId
        // won't fail on unknown names (skipped when autoRegisterMetadata is false)
        if (autoRegisterMetadata) {
            ensureMetadataRegistered(scope);
        }

        // Step 1: DB write (source of truth)
        jooqAdmin.revokeConsent(subject, scope, expiresAt);

        // Step 2: Redis sync (best-effort)
        syncToRedis(subject, scope, false, expiresAt);
    }

    // -----------------------------------------------------------------------
    // Accessors (post-configuration)
    // -----------------------------------------------------------------------

    /**
     * Returns the underlying {@link JooqConsentAdminService}.
     *
     * <p>Primarily useful for tests and advanced callers that need direct
     * access to the relational admin layer.
     *
     * @return the jOOQ admin service
     * @throws IllegalStateException if not yet configured
     */
    public JooqConsentAdminService jooqAdminService() {
        ensureConfigured();
        return jooqAdmin;
    }

    /**
     * Returns the underlying {@link ConsentPolicyRedisRepository}.
     *
     * @return the Redis consent policy repository
     * @throws IllegalStateException if not yet configured
     */
    public ConsentPolicyRedisRepository redisConsentRepository() {
        ensureConfigured();
        return redisConsentRepo;
    }

    /**
     * Returns the underlying {@link FieldCategoryMappingRedisRepository}.
     *
     * @return the Redis field-category mapping repository
     * @throws IllegalStateException if not yet configured
     */
    public FieldCategoryMappingRedisRepository redisMappingRepository() {
        ensureConfigured();
        return redisMappingRepo;
    }

    // -----------------------------------------------------------------------
    // Internal — auto-registration
    // -----------------------------------------------------------------------

    /**
     * Ensures that all metadata referenced by the given scope exists in
     * both the relational admin schema and the Redis field-category
     * mapping repository.
     *
     * <p>This is a safety net so that consent grants and revocations never
     * fail simply because the caller forgot to annotate a class or missed
     * it in their discovery config. Discovery remains the primary mechanism
     * for bulk registration at startup; this method catches anything that
     * slipped through.
     *
     * <p>All registration calls use upsert semantics, so calling this
     * method when the metadata already exists is a harmless no-op.
     *
     * <h3>What is registered</h3>
     * <ul>
     *   <li>If the scope has a non-null {@code category}: the category is
     *       registered in the admin schema.</li>
     *   <li>If the scope has a non-null {@code fieldName}: the field (and
     *       its owning entity, if {@code entityName} is non-null) is
     *       registered in the admin schema.</li>
     *   <li>If both {@code fieldName} and {@code category} are present:
     *       the field-to-category link is created in both the admin schema
     *       and the Redis mapping repository. This ensures that subsequent
     *       category-level fan-out writes will discover the field.</li>
     * </ul>
     *
     * @param scope the consent scope whose metadata should be ensured
     */
    private void ensureMetadataRegistered(ConsentScope scope) {
        try {
            // Register purpose if present — required since V4 made
            // purpose_id NOT NULL on consent_policy. Without this,
            // resolvePurposeId() would throw on unknown purpose names.
            if (scope.purposeName() != null && !scope.purposeName().isBlank()) {
                jooqAdmin.registerPurpose(scope.purposeName(), null);
            }

            // Register category if present
            if (scope.category() != null && !scope.category().isBlank()) {
                jooqAdmin.registerCategory(scope.category(), null);
            }

            // Register field (and its entity) if present
            if (scope.fieldName() != null && !scope.fieldName().isBlank()) {
                jooqAdmin.registerField(
                    scope.fieldName(),
                    scope.entityName(),
                    null
                );

                // Link field ↔ category in both the admin schema and the
                // Redis mapping repo so category fan-out works correctly.
                if (scope.category() != null && !scope.category().isBlank()) {
                    jooqAdmin.linkFieldToCategory(
                        scope.fieldName(),
                        scope.entityName(),
                        scope.category()
                    );
                    redisMappingRepo.addFieldToCategory(
                        scope.entityName(),
                        scope.fieldName(),
                        scope.category()
                    );
                }
            }
        } catch (Exception e) {
            // Auto-registration is best-effort. If it fails (e.g. because
            // of an ambiguous null-entity field name when entity-scoped
            // fields already exist, or a transient DB/Redis error), log a
            // warning and let the subsequent grantConsent / revokeConsent
            // call proceed — it will either succeed (metadata already
            // registered via another path such as discovery) or fail with
            // a clear, descriptive error from the admin layer.
            log.log(
                Level.FINE,
                "Auto-registration of metadata skipped for scope={0}: {1}",
                new Object[] { scope, e.getMessage() }
            );
        }
    }

    // -----------------------------------------------------------------------
    // Internal — Redis sync
    // -----------------------------------------------------------------------

    /**
     * Syncs a consent decision to Redis, handling field-level and
     * category-level writes appropriately.
     *
     * <p>Field-level writes produce a single Redis key. Category-level
     * writes fan out to all fields in the category via
     * {@link FieldCategoryMappingRedisRepository#getFieldsForCategory},
     * respecting field-level precedence (existing {@code FIELD}-sourced
     * entries are never clobbered).
     *
     * <p>Any exception thrown during the Redis write is caught and logged.
     * The DB write (which has already completed) is never rolled back.
     *
     * @param subject   the data subject identifier
     * @param scope     the consent scope
     * @param granted   {@code true} for grant, {@code false} for revoke
     * @param expiresAt when the consent expires, or {@code null} for no TTL
     */
    private void syncToRedis(
        String subject,
        ConsentScope scope,
        boolean granted,
        Instant expiresAt
    ) {
        try {
            if (scope.fieldName() != null) {
                // Field-level: single key with SOURCE_FIELD
                redisConsentRepo.setFieldConsent(
                    subject,
                    scope.entityName(),
                    scope.fieldName(),
                    scope.purposeName(),
                    granted,
                    expiresAt
                );
            } else if (scope.category() != null) {
                // Category-level: fan out to all fields in the category.
                // getFieldsForCategory returns qualified field names
                // (entity/field) that are used directly by the consent repo.
                Set<String> qualifiedFields =
                    redisMappingRepo.getFieldsForCategory(scope.category());
                redisConsentRepo.setCategoryConsentForFields(
                    subject,
                    scope.purposeName(),
                    granted,
                    expiresAt,
                    qualifiedFields
                );
            }
        } catch (OperioServiceException e) {
            // Transient infrastructure failure (Redis timeout, connection pool
            // exhausted). Redis may be stale until next reconciliation or retry.
            log.log(
                Level.WARNING,
                "Redis sync failed (retryable) for subject={0} scope={1} granted={2}. " +
                    "Redis may be stale until next reconciliation.",
                new Object[] { subject, scope, granted }
            );
            log.log(Level.WARNING, "Redis sync exception details", e);
        } catch (Exception e) {
            // Unexpected error — not a known transient failure.
            log.log(
                Level.SEVERE,
                "Redis sync failed (unexpected) for subject={0} scope={1} granted={2}. " +
                    "Redis may be stale until next reconciliation.",
                new Object[] { subject, scope, granted }
            );
            log.log(Level.SEVERE, "Redis sync exception details", e);
        }
    }

    /**
     * Returns {@code true} if this plugin is configured and the underlying
     * jOOQ admin service is ready.
     *
     * <p>Redis readiness is best-effort; the relational DB is the source of
     * truth, so this check delegates to the jOOQ admin service's readiness.
     * Returns {@code false} if the service has not been configured or if the
     * delegate check fails for any reason.
     *
     * @return {@code true} if the plugin is ready to serve requests
     */
    @Override
    public boolean isReady() {
        if (!configured || jooqAdmin == null) {
            return false;
        }
        try {
            return jooqAdmin.isReady();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Guards against use before {@link #configure} has been called.
     *
     * @throws OperioConfigurationException if the service is not yet configured
     */
    private void ensureConfigured() {
        if (!configured) {
            throw new OperioConfigurationException(
                "ConsentSyncServiceImpl has not been configured. " +
                    "Call configure(ConsentSyncServiceConfig) first."
            );
        }
    }
}
