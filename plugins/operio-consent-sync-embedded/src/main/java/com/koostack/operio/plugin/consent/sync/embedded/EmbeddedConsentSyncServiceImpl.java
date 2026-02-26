package com.koostack.operio.plugin.consent.sync.embedded;

import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.service.ConsentSyncService;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentDecisionService;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentRepository;
import com.koostack.operio.plugin.consent.caffeine.CaffeineFieldCategoryMappingRepository;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminService;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dual-write coordinator that keeps the relational consent admin store
 * (jOOQ/H2) and the Caffeine in-memory decision cache in sync.
 *
 * <p>This is the embedded counterpart of {@code ConsentSyncServiceImpl}
 * (which targets Redis). It provides the same write semantics — DB first,
 * cache second — but stores the decision cache in-process via Caffeine
 * rather than in an external Redis server.
 *
 * <h2>Write Strategy</h2>
 * <ol>
 *   <li>Write to the relational DB via {@link JooqConsentAdminService}
 *       (source of truth).</li>
 *   <li>Sync to the Caffeine cache via {@link CaffeineConsentRepository}
 *       and {@link CaffeineFieldCategoryMappingRepository}.</li>
 * </ol>
 *
 * <h2>Error Handling</h2>
 * <p>The DB write is always performed first. If the subsequent cache write
 * fails, the error is caught and logged — the DB row is <em>never</em>
 * rolled back on cache failure. The stale cache entry will be corrected
 * by a future reconciliation job or a subsequent successful sync.
 *
 * <h2>Category Fan-Out</h2>
 * <p>Category-level writes are materialized (fanned out) into individual
 * field entries at write time. The fan-out respects field-level precedence:
 * existing {@code FIELD}-sourced entries in the cache are never overwritten
 * by a category fan-out.
 *
 * <h2>TTL Handling</h2>
 * <p>When an {@code expiresAt} timestamp is provided, the Caffeine entries
 * are written with per-entry expiration via Caffeine's variable expiry
 * policy. If the computed TTL is zero or negative, the cache write is
 * skipped (the entry is already expired). If {@code expiresAt} is
 * {@code null}, no expiry is set.
 *
 * <h2>Instance Sharing</h2>
 * <p>Because the Caffeine cache is in-process (unlike Redis, which is an
 * external shared store), the same {@link CaffeineConsentDecisionService}
 * and {@link JooqConsentAdminService} instances used by this sync service
 * <strong>must</strong> be the same instances wired into the
 * {@link com.koostack.operio.core.Operio.Builder} as the consent decision
 * and admin providers respectively. The
 * {@link com.koostack.operio.bundle.embedded.EmbeddedConsentBundle}
 * achieves this by creating the sync service first (via
 * {@link #configure(EmbeddedConsentSyncServiceConfig)}), then extracting
 * the internal services via {@link #jooqAdminService()} and
 * {@link #caffeineDecisionService()} and wiring those same instances into
 * the builder.
 *
 * @see ConsentSyncService
 * @see JooqConsentAdminService
 * @see CaffeineConsentRepository
 */
public class EmbeddedConsentSyncServiceImpl
    implements
        ConsentSyncService<EmbeddedConsentSyncServiceConfig>,
        Operio.AppNameAware
{

    private static final Logger log = Logger.getLogger(
        EmbeddedConsentSyncServiceImpl.class.getName()
    );

    private JooqConsentAdminService jooqAdmin;
    private CaffeineConsentDecisionService caffeineDecision;
    private CaffeineConsentRepository caffeineConsentRepo;
    private CaffeineFieldCategoryMappingRepository caffeineMappingRepo;
    private CaffeineReconciliationHelper reconciliationHelper;
    private boolean autoRegisterMetadata = true;
    private boolean configured;

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "embedded-sync";
    }

    @Override
    public int getPriority() {
        return 300;
    }

    @Override
    public Class<EmbeddedConsentSyncServiceConfig> getConfigurationType() {
        return EmbeddedConsentSyncServiceConfig.class;
    }

    /**
     * Configures this sync service by creating internal
     * {@link JooqConsentAdminService} and
     * {@link CaffeineConsentDecisionService} instances from the composite
     * config.
     *
     * <p>After this method returns, callers (typically a bundle) can
     * retrieve the shared instances via {@link #jooqAdminService()} and
     * {@link #caffeineDecisionService()} and wire them into the
     * {@link com.koostack.operio.core.Operio.Builder} so that all three
     * roles (admin, sync, decision) share the same objects.
     *
     * @param config the composite configuration
     */
    @Override
    public void configure(EmbeddedConsentSyncServiceConfig config) {
        // Initialize the jOOQ admin service with its own config
        this.jooqAdmin = new JooqConsentAdminService();
        this.jooqAdmin.configure(config.jooq());

        // Initialize the Caffeine decision service
        this.caffeineDecision = new CaffeineConsentDecisionService();
        this.caffeineDecision.configure(config.caffeine());
        this.caffeineConsentRepo = this.caffeineDecision.consentRepository();
        this.caffeineMappingRepo =
            this.caffeineDecision.fieldCategoryMappingRepository();

        this.autoRegisterMetadata = config.autoRegisterMetadata();
        this.configured = true;
    }

    // -----------------------------------------------------------------------
    // AppNameAware
    // -----------------------------------------------------------------------

    /**
     * Propagates the application name to the underlying Caffeine decision
     * service so that diagnostic/logging information is consistent.
     *
     * @param appName the application name, never {@code null}
     */
    @Override
    public void setAppName(String appName) {
        if (caffeineDecision != null) {
            caffeineDecision.setAppName(appName);
        }
    }

    // -----------------------------------------------------------------------
    // ConsentSyncService
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Writes a grant to the relational DB, then syncs the grant to the
     * Caffeine cache.
     *
     * <p>Before the DB write, any entities, fields, and categories
     * referenced by the scope are auto-registered in the admin schema
     * (idempotent upsert). This ensures the embedded bundle works
     * without requiring an explicit discovery/registration step.
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

        // Step 2: Caffeine cache sync (best-effort)
        syncToCache(subject, scope, true, expiresAt);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes a revocation to the relational DB, then syncs the
     * revocation to the Caffeine cache.
     *
     * <p>Before the DB write, any entities, fields, and categories
     * referenced by the scope are auto-registered in the admin schema
     * (idempotent upsert). This ensures the embedded bundle works
     * without requiring an explicit discovery/registration step.
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

        // Step 2: Caffeine cache sync (best-effort)
        syncToCache(subject, scope, false, expiresAt);
    }

    // -----------------------------------------------------------------------
    // Accessors (post-configuration)
    // -----------------------------------------------------------------------

    /**
     * Returns the underlying {@link JooqConsentAdminService}.
     *
     * <p>The embedded bundle extracts this instance and wires it as the
     * builder's {@code ConsentAdminService} so that schema registration
     * (field, category, and link operations from the discovery pipeline)
     * goes through the same jOOQ instance that this sync service writes to.
     *
     * @return the jOOQ admin service
     * @throws OperioConfigurationException if not yet configured
     */
    public JooqConsentAdminService jooqAdminService() {
        ensureConfigured();
        return jooqAdmin;
    }

    /**
     * Returns the underlying {@link CaffeineConsentDecisionService}.
     *
     * <p>The embedded bundle extracts this instance and wires it as the
     * builder's {@code ConsentDecisionService} so that consent evaluation
     * reads from the same Caffeine cache that this sync service writes to.
     *
     * @return the Caffeine consent decision service
     * @throws OperioConfigurationException if not yet configured
     */
    public CaffeineConsentDecisionService caffeineDecisionService() {
        ensureConfigured();
        return caffeineDecision;
    }

    /**
     * Returns the underlying {@link CaffeineConsentRepository}.
     *
     * <p>Primarily useful for tests and advanced callers that need direct
     * access to the consent cache.
     *
     * @return the Caffeine consent repository
     * @throws OperioConfigurationException if not yet configured
     */
    public CaffeineConsentRepository caffeineConsentRepository() {
        ensureConfigured();
        return caffeineConsentRepo;
    }

    /**
     * Returns the underlying {@link CaffeineFieldCategoryMappingRepository}.
     *
     * <p>Primarily useful for tests and advanced callers that need direct
     * access to the field-category metadata cache.
     *
     * @return the Caffeine field-category mapping repository
     * @throws OperioConfigurationException if not yet configured
     */
    public CaffeineFieldCategoryMappingRepository caffeineMappingRepository() {
        ensureConfigured();
        return caffeineMappingRepo;
    }

    // -----------------------------------------------------------------------
    // Reconciliation — DB → Caffeine cache
    // -----------------------------------------------------------------------

    /**
     * Creates (or returns a cached) {@link CaffeineReconciliationHelper}
     * wired to this sync service's jOOQ admin and Caffeine repositories.
     *
     * <p>The helper is lazily created on first call and reused thereafter.
     * It is safe to call from any thread.
     *
     * @return the reconciliation helper
     * @throws OperioConfigurationException if not yet configured
     */
    public CaffeineReconciliationHelper reconciliationHelper() {
        ensureConfigured();
        if (reconciliationHelper == null) {
            reconciliationHelper = new CaffeineReconciliationHelper(
                jooqAdmin,
                caffeineConsentRepo,
                caffeineMappingRepo
            );
        }
        return reconciliationHelper;
    }

    /**
     * Reconciles the entire Caffeine cache from the relational DB using
     * the default strategy ({@link ReconciliationStrategy#FULL_REBUILD}).
     *
     * <p>This is the primary entry point for cache rehydration. It
     * delegates to
     * {@link CaffeineReconciliationHelper#reconcile(ReconciliationStrategy)}
     * and returns the result so callers can log/meter the outcome.
     *
     * <h3>Typical usage</h3>
     * <pre>{@code
     * // At application startup, after configuring the sync service:
     * ReconciliationResult result = syncService.reconcileFromDb();
     * log.info(result.summary());
     * }</pre>
     *
     * @return the reconciliation result with counts and elapsed time
     * @throws OperioConfigurationException if not yet configured
     * @see #reconcileFromDb(ReconciliationStrategy)
     * @see CaffeineReconciliationHelper#reconcile()
     */
    public ReconciliationResult reconcileFromDb() {
        return reconcileFromDb(ReconciliationStrategy.FULL_REBUILD);
    }

    /**
     * Reconciles the entire Caffeine cache from the relational DB using
     * the specified strategy.
     *
     * @param strategy the reconciliation strategy to use
     * @return the reconciliation result with counts and elapsed time
     * @throws OperioConfigurationException if not yet configured
     * @throws NullPointerException if {@code strategy} is {@code null}
     * @see ReconciliationStrategy
     * @see CaffeineReconciliationHelper#reconcile(ReconciliationStrategy)
     */
    public ReconciliationResult reconcileFromDb(
        ReconciliationStrategy strategy
    ) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        return reconciliationHelper().reconcile(strategy);
    }

    /**
     * Reconciles consent data for a single subject from the relational DB
     * using the default strategy
     * ({@link ReconciliationStrategy#FULL_REBUILD}).
     *
     * <p>Only the specified subject's consent entries are affected. When
     * using {@link ReconciliationStrategy#FULL_REBUILD}, only that
     * subject's cache entries are cleared — other subjects and
     * field↔category mappings are not touched.
     *
     * @param subjectId the subject whose consent entries to reconcile
     * @return the reconciliation result with counts and elapsed time
     * @throws OperioConfigurationException if not yet configured
     * @throws NullPointerException if {@code subjectId} is {@code null}
     * @see CaffeineReconciliationHelper#reconcileSubject(String)
     */
    public ReconciliationResult reconcileSubjectFromDb(String subjectId) {
        return reconcileSubjectFromDb(
            subjectId,
            ReconciliationStrategy.FULL_REBUILD
        );
    }

    /**
     * Reconciles consent data for a single subject from the relational DB
     * using the specified strategy.
     *
     * @param subjectId the subject whose consent entries to reconcile
     * @param strategy  the reconciliation strategy to use
     * @return the reconciliation result with counts and elapsed time
     * @throws OperioConfigurationException if not yet configured
     * @throws NullPointerException if any argument is {@code null}
     * @see CaffeineReconciliationHelper#reconcileSubject(String,
     *      ReconciliationStrategy)
     */
    public ReconciliationResult reconcileSubjectFromDb(
        String subjectId,
        ReconciliationStrategy strategy
    ) {
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        return reconciliationHelper().reconcileSubject(subjectId, strategy);
    }

    // -----------------------------------------------------------------------
    // Internal — auto-registration
    // -----------------------------------------------------------------------

    /**
     * Ensures that all metadata referenced by the given scope exists in
     * both the relational admin schema and the Caffeine field-category
     * mapping repository.
     *
     * <p>This is the key enabler for the embedded bundle's "zero-config"
     * grant/revoke UX: callers do not need to run a discovery pipeline or
     * manually call {@code registerField} / {@code registerCategory}
     * before granting or revoking consent.
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
     *       and the Caffeine mapping repository. This ensures that
     *       subsequent category-level fan-out writes will discover the
     *       field.</li>
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
                // Caffeine mapping repo so category fan-out works correctly.
                if (scope.category() != null && !scope.category().isBlank()) {
                    jooqAdmin.linkFieldToCategory(
                        scope.fieldName(),
                        scope.entityName(),
                        scope.category()
                    );
                    caffeineMappingRepo.addFieldToCategory(
                        scope.entityName(),
                        scope.fieldName(),
                        scope.category()
                    );
                }
            }
        } catch (Exception e) {
            // Auto-registration is best-effort. If it fails (e.g. because
            // of an ambiguous null-entity field name when entity-scoped
            // fields already exist, or a transient DB error), log a warning
            // and let the subsequent grantConsent / revokeConsent call
            // proceed — it will either succeed (metadata already registered
            // via another path such as discovery) or fail with a clear,
            // descriptive error from the admin layer.
            log.log(
                Level.FINE,
                "Auto-registration of metadata skipped for scope={0}: {1}",
                new Object[] { scope, e.getMessage() }
            );
        }
    }

    // -----------------------------------------------------------------------
    // Internal — cache sync
    // -----------------------------------------------------------------------

    /**
     * Syncs a consent decision to the Caffeine cache, handling field-level
     * and category-level writes appropriately.
     *
     * <p>Field-level writes produce a single cache entry. Category-level
     * writes fan out to all fields in the category via
     * {@link CaffeineFieldCategoryMappingRepository#getFieldsForCategory},
     * respecting field-level precedence (existing {@code FIELD}-sourced
     * entries are never clobbered).
     *
     * <p>Any exception thrown during the cache write is caught and logged.
     * The DB write (which has already completed) is never rolled back.
     *
     * @param subject   the data subject identifier
     * @param scope     the consent scope
     * @param granted   {@code true} for grant, {@code false} for revoke
     * @param expiresAt when the consent expires, or {@code null} for no TTL
     */
    private void syncToCache(
        String subject,
        ConsentScope scope,
        boolean granted,
        Instant expiresAt
    ) {
        try {
            if (scope.fieldName() != null) {
                // Field-level: single cache entry with SOURCE_FIELD
                caffeineConsentRepo.setFieldConsent(
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
                    caffeineMappingRepo.getFieldsForCategory(scope.category());
                caffeineConsentRepo.setCategoryConsentForFields(
                    subject,
                    scope.purposeName(),
                    granted,
                    expiresAt,
                    qualifiedFields
                );
            }
        } catch (OperioServiceException e) {
            // Known infrastructure failure — should not happen with
            // in-memory stores, but handled defensively.
            log.log(
                Level.WARNING,
                "Cache sync failed (retryable) for subject={0} scope={1} granted={2}. " +
                    "Cache may be stale until next reconciliation.",
                new Object[] { subject, scope, granted }
            );
            log.log(Level.WARNING, "Cache sync exception details", e);
        } catch (Exception e) {
            // Unexpected error.
            log.log(
                Level.SEVERE,
                "Cache sync failed (unexpected) for subject={0} scope={1} granted={2}. " +
                    "Cache may be stale until next reconciliation.",
                new Object[] { subject, scope, granted }
            );
            log.log(Level.SEVERE, "Cache sync exception details", e);
        }
    }

    // -----------------------------------------------------------------------
    // Health check
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if this plugin is configured and the underlying
     * jOOQ admin service is ready.
     *
     * <p>The Caffeine cache is always ready once configured (it is
     * in-process), so the health check delegates to the jOOQ admin
     * service's readiness. Returns {@code false} if the service has not
     * been configured or if the delegate check fails for any reason.
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

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    /**
     * Guards against use before {@link #configure} has been called.
     *
     * @throws OperioConfigurationException if the service is not yet configured
     */
    private void ensureConfigured() {
        if (!configured) {
            throw new OperioConfigurationException(
                "EmbeddedConsentSyncServiceImpl has not been configured. " +
                    "Call configure(EmbeddedConsentSyncServiceConfig) first."
            );
        }
    }
}
