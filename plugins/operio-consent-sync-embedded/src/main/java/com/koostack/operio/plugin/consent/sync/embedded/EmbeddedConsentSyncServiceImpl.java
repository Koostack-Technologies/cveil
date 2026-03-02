package com.koostack.operio.plugin.consent.sync.embedded;

import com.koostack.operio.core.models.ConsentScope;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentDecisionService;
import com.koostack.operio.plugin.consent.caffeine.CaffeineConsentRepository;
import com.koostack.operio.plugin.consent.caffeine.CaffeineFieldCategoryMappingRepository;
import com.koostack.operio.plugin.consent.jooq.ConsentAdminCommandConfig;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminCommandService;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminQueryService;
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
 * (which targets Redis). It provides the same write semantics — command
 * service first (publishes domain events), cache second — but stores the
 * decision cache in-process via Caffeine rather than in an external Redis
 * server.
 *
 * <h2>Write Strategy</h2>
 * <ol>
 *   <li>Publish domain events via {@link JooqConsentAdminCommandService}
 *       (when an event service is configured).</li>
 *   <li>Sync to the Caffeine cache via {@link CaffeineConsentRepository}
 *       and {@link CaffeineFieldCategoryMappingRepository}.</li>
 * </ol>
 *
 * <h2>Error Handling</h2>
 * <p>The command write is always performed first. If the subsequent cache
 * write fails, the error is caught and logged — the command is
 * <em>never</em> rolled back on cache failure.
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
 * and {@link JooqConsentAdminQueryService} instances used by this sync
 * service <strong>must</strong> be the same instances wired into the
 * {@link com.koostack.operio.core.Operio.Builder} as the consent decision
 * and admin providers respectively.
 *
 * @see JooqConsentAdminCommandService
 * @see JooqConsentAdminQueryService
 * @see CaffeineConsentRepository
 */
public class EmbeddedConsentSyncServiceImpl
    implements Operio.AppNameAware
{

    private static final Logger log = Logger.getLogger(
        EmbeddedConsentSyncServiceImpl.class.getName()
    );

    private JooqConsentAdminCommandService commandService;
    private JooqConsentAdminQueryService queryService;
    private CaffeineConsentDecisionService caffeineDecision;
    private CaffeineConsentRepository caffeineConsentRepo;
    private CaffeineFieldCategoryMappingRepository caffeineMappingRepo;
    private CaffeineReconciliationHelper reconciliationHelper;
    private boolean autoRegisterMetadata = true;
    private boolean configured;

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    public String getName() {
        return "embedded-sync";
    }

    public int getPriority() {
        return 300;
    }

    public Class<EmbeddedConsentSyncServiceConfig> getConfigurationType() {
        return EmbeddedConsentSyncServiceConfig.class;
    }

    /**
     * Configures this sync service by creating internal
     * {@link JooqConsentAdminCommandService},
     * {@link JooqConsentAdminQueryService}, and
     * {@link CaffeineConsentDecisionService} instances from the composite
     * config.
     *
     * <p>After this method returns, callers (typically a bundle) can
     * retrieve the shared instances via {@link #commandService()},
     * {@link #queryService()}, and {@link #caffeineDecisionService()} and
     * wire them into the {@link com.koostack.operio.core.Operio.Builder}
     * so that all roles share the same objects.
     *
     * @param config the composite configuration
     */
    public void configure(EmbeddedConsentSyncServiceConfig config) {
        // Initialize the jOOQ query service (for reads / reconciliation)
        this.queryService = new JooqConsentAdminQueryService();
        this.queryService.configure(config.jooq());

        // Initialize the command service if an event service is provided
        if (config.eventService() != null) {
            this.commandService = new JooqConsentAdminCommandService();
            this.commandService.configure(
                new ConsentAdminCommandConfig(config.eventService())
            );
        }

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
    // Consent Sync Operations
    // -----------------------------------------------------------------------

    /**
     * Writes a grant to the command service (publishing a domain event),
     * then syncs the grant to the Caffeine cache.
     *
     * <p>Before the command, any entities, fields, and categories
     * referenced by the scope are auto-registered via the command service
     * (idempotent). This ensures the embedded bundle works without
     * requiring an explicit discovery/registration step.
     */
    public void syncGrant(
        String subject,
        ConsentScope scope,
        Instant expiresAt
    ) {
        ensureConfigured();

        // Step 0: auto-register metadata so downstream resolvers won't
        // fail on unknown names (skipped when autoRegisterMetadata is false)
        if (autoRegisterMetadata) {
            ensureMetadataRegistered(scope);
        }

        // Step 1: Command service write (publishes domain event)
        if (commandService != null) {
            String policyType = scope.isCategoryScoped() ? "CATEGORY" : "FIELD";
            long policyId = resolvePolicyId(scope, policyType);
            commandService.grantConsentCommand(
                subject, policyId, scope.purposeName(), policyType,
                scope.isCategoryScoped() ? null : scope.fieldName(),
                scope.isCategoryScoped() ? null : scope.entityName(),
                scope.isCategoryScoped() ? scope.category() : null,
                null, null, null, expiresAt
            );
        }

        // Step 2: Caffeine cache sync (best-effort)
        syncToCache(subject, scope, true, expiresAt);
    }

    /**
     * Writes a revocation to the command service (publishing a domain
     * event), then syncs the revocation to the Caffeine cache.
     *
     * <p>Before the command, any entities, fields, and categories
     * referenced by the scope are auto-registered via the command service
     * (idempotent). This ensures the embedded bundle works without
     * requiring an explicit discovery/registration step.
     */
    public void syncRevoke(
        String subject,
        ConsentScope scope,
        Instant expiresAt
    ) {
        ensureConfigured();

        // Step 0: auto-register metadata so downstream resolvers won't
        // fail on unknown names (skipped when autoRegisterMetadata is false)
        if (autoRegisterMetadata) {
            ensureMetadataRegistered(scope);
        }

        // Step 1: Command service write (publishes domain event)
        if (commandService != null) {
            String policyType = scope.isCategoryScoped() ? "CATEGORY" : "FIELD";
            long policyId = resolvePolicyId(scope, policyType);
            commandService.revokeConsentCommand(
                subject, policyId, scope.purposeName(), policyType,
                scope.isCategoryScoped() ? null : scope.fieldName(),
                scope.isCategoryScoped() ? null : scope.entityName(),
                scope.isCategoryScoped() ? scope.category() : null,
                null, null, null
            );
        }

        // Step 2: Caffeine cache sync (best-effort)
        syncToCache(subject, scope, false, expiresAt);
    }

    // -----------------------------------------------------------------------
    // Accessors (post-configuration)
    // -----------------------------------------------------------------------

    /**
     * Returns the underlying {@link JooqConsentAdminCommandService}, or
     * {@code null} if no event service was provided at configuration time.
     *
     * @return the command service, or {@code null}
     * @throws OperioConfigurationException if not yet configured
     */
    public JooqConsentAdminCommandService commandService() {
        ensureConfigured();
        return commandService;
    }

    /**
     * Returns the underlying {@link JooqConsentAdminQueryService}.
     *
     * <p>The embedded bundle extracts this instance and wires it as the
     * builder's query service so that read operations go through the same
     * jOOQ instance that this sync service reads from.
     *
     * @return the jOOQ query service
     * @throws OperioConfigurationException if not yet configured
     */
    public JooqConsentAdminQueryService queryService() {
        ensureConfigured();
        return queryService;
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
     * wired to this sync service's query service and Caffeine repositories.
     *
     * @return the reconciliation helper
     * @throws OperioConfigurationException if not yet configured
     */
    public CaffeineReconciliationHelper reconciliationHelper() {
        ensureConfigured();
        if (reconciliationHelper == null) {
            reconciliationHelper = new CaffeineReconciliationHelper(
                queryService,
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
     * @see ReconciliationStrategy
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
     * @param subjectId the subject whose consent entries to reconcile
     * @return the reconciliation result with counts and elapsed time
     * @throws OperioConfigurationException if not yet configured
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
    // Internal — policy resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves a ConsentScope + policyType to a policy_id via the query
     * service. Returns 0 if no matching policy exists (best-effort for
     * embedded mode where policies may not yet be created).
     */
    private long resolvePolicyId(ConsentScope scope, String policyType) {
        try {
            var policy = queryService.findPolicy(
                scope.purposeName(), policyType,
                scope.isCategoryScoped() ? null : scope.fieldName(),
                scope.isCategoryScoped() ? null : scope.entityName(),
                scope.isCategoryScoped() ? scope.category() : null
            );
            return policy != null ? policy.policyId() : 0L;
        } catch (Exception e) {
            log.log(java.util.logging.Level.FINE,
                "Could not resolve policy for scope {0} — using policyId=0",
                new Object[]{ scope });
            return 0L;
        }
    }

    // -----------------------------------------------------------------------
    // Internal — auto-registration
    // -----------------------------------------------------------------------

    /**
     * Ensures that all metadata referenced by the given scope exists by
     * publishing registration commands via the command service.
     *
     * <p>All registration commands use idempotent semantics.
     *
     * @param scope the consent scope whose metadata should be ensured
     */
    private void ensureMetadataRegistered(ConsentScope scope) {
        if (commandService == null) {
            return; // no command service — cache-only mode
        }
        try {
            // Register purpose if present
            if (!ConsentScope.WILDCARD.equals(scope.purposeName())) {
                commandService.registerPurposeCommand(scope.purposeName(), null);
            }

            // Register field (and its entity) if present
            if (!scope.isCategoryScoped()) {
                commandService.registerFieldCommand(
                    scope.fieldName(),
                    scope.entityName(),
                    null,
                    null
                );

                // Assign field ↔ category (also auto-creates category) and
                // populate Caffeine mapping repo so category fan-out works.
                if (!ConsentScope.WILDCARD.equals(scope.category())) {
                    commandService.assignFieldCategoryCommand(
                        scope.fieldName(),
                        scope.entityName(),
                        scope.category(),
                        null,
                        null
                    );
                    caffeineMappingRepo.addFieldToCategory(
                        scope.entityName(),
                        scope.fieldName(),
                        scope.category()
                    );
                }
            }
        } catch (Exception e) {
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
     * Syncs a consent decision to the Caffeine cache.
     */
    private void syncToCache(
        String subject,
        ConsentScope scope,
        boolean granted,
        Instant expiresAt
    ) {
        try {
            if (scope.isCategoryScoped()) {
                // Category-level: fan out to all fields in the category
                Set<String> qualifiedFields =
                    caffeineMappingRepo.getFieldsForCategory(scope.category());
                caffeineConsentRepo.setCategoryConsentForFields(
                    subject,
                    scope.purposeName(),
                    granted,
                    expiresAt,
                    qualifiedFields
                );
            } else {
                // Field-level: single cache entry
                caffeineConsentRepo.setFieldConsent(
                    subject,
                    scope.entityName(),
                    scope.fieldName(),
                    scope.purposeName(),
                    granted,
                    expiresAt
                );
            }
        } catch (OperioServiceException e) {
            log.log(
                Level.WARNING,
                "Cache sync failed (retryable) for subject={0} scope={1} granted={2}. " +
                    "Cache may be stale until next reconciliation.",
                new Object[] { subject, scope, granted }
            );
            log.log(Level.WARNING, "Cache sync exception details", e);
        } catch (Exception e) {
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
     * query service is ready.
     *
     * @return {@code true} if the plugin is ready to serve requests
     */
    public boolean isReady() {
        if (!configured || queryService == null) {
            return false;
        }
        try {
            return queryService.isReady();
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
