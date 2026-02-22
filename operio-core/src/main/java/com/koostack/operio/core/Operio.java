package com.koostack.operio.core;

import com.koostack.operio.core.Models.ConsentScope;
import com.koostack.operio.core.Models.DiscoveredField;
import com.koostack.operio.core.Models.DiscoveryResult;
import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.registry.PluginRegistry;
import com.koostack.operio.core.service.AuditLogger;
import com.koostack.operio.core.service.ConsentAdminService;
import com.koostack.operio.core.service.ConsentDecisionService;
import com.koostack.operio.core.service.ConsentSyncService;
import com.koostack.operio.core.service.IdentityResolver;
import com.koostack.operio.core.service.MaskingStrategy;
import com.koostack.operio.core.service.SensitiveDiscovery;
import com.koostack.operio.core.service.SensitiveFieldResolver;
import com.koostack.operio.core.spi.Plugin;
import com.koostack.operio.core.spi.PluginBundle;
import com.koostack.operio.core.spi.PluginConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Primary entry point to the Operio consent management API.
 *
 * <p>Provides both the <strong>read path</strong> (consent evaluation +
 * data masking via {@link #access(Object)}) and the <strong>write path</strong>
 * (granting / revoking consent via {@link #grantConsent} and
 * {@link #revokeConsent}).
 *
 * <h2>Write-Path Routing</h2>
 * <p>When a {@link ConsentSyncService} is configured it is preferred for all
 * consent writes because it coordinates a dual-write to both the relational
 * store and the cache layer. If only a {@link ConsentAdminService} is present,
 * writes go directly to the relational store. If neither is configured the
 * write methods throw {@link OperioConfigurationException}.
 *
 * <h2>Construction</h2>
 * <p>Use {@link #builder()} to obtain a {@link Builder}, wire up the desired
 * providers, then call {@link Builder#build()}.
 *
 * <pre>{@code
 * Operio operio = Operio.builder()
 *     .withConsentProvider("redis")
 *     .withConsentSyncProvider("consent-sync", syncConfig)
 *     .withIdentityProvider("http")
 *     .withAuditProvider("slf4j")
 *     .withMaskingProvider("jackson")
 *     .withSensitiveFieldResolver("annotation")
 *     .build();
 * }</pre>
 */
public class Operio {

    private static final Logger LOG = Logger.getLogger(Operio.class.getName());

    /** Default application name used when none is explicitly configured. */
    public static final String DEFAULT_APP_NAME = "operio";

    // -- Instance identity ----------------------------------------------------
    private final String appName;

    // -- Read-path plugins ----------------------------------------------------
    private final ConsentDecisionService<?> consentService;
    private final IdentityResolver<?> identityResolver;
    private final AuditLogger auditLogger;
    private final MaskingStrategy<?> maskingStrategy;
    private final SensitiveFieldResolver<?> sensitiveFieldResolver;

    // -- Write-path plugins ---------------------------------------------------
    private final ConsentAdminService<?> consentAdminService;
    private final ConsentSyncService<?> consentSyncService;

    // -- Discovery plugins ----------------------------------------------------
    private final SensitiveDiscovery<?> sensitiveDiscovery;

    public Operio(Builder builder) {
        this.appName =
            builder.appName != null ? builder.appName : DEFAULT_APP_NAME;
        this.consentService = builder.consentService;
        this.identityResolver = builder.identityResolver;
        this.auditLogger = builder.auditLogger;
        this.maskingStrategy = builder.maskingStrategy;
        this.sensitiveFieldResolver = builder.sensitiveFieldResolver;
        this.consentAdminService = builder.consentAdminService;
        this.consentSyncService = builder.consentSyncService;
        this.sensitiveDiscovery = builder.sensitiveDiscovery;

        // Propagate appName to any service that supports namespacing
        propagateAppName(consentService);
        propagateAppName(consentAdminService);
        propagateAppName(consentSyncService);
    }

    /**
     * If the service implements {@link AppNameAware}, push this instance's
     * {@link #appName} into it so that Redis keys (and any other
     * namespace-sensitive artefacts) are scoped correctly.
     */
    private void propagateAppName(Object service) {
        if (service instanceof AppNameAware a) {
            a.setAppName(this.appName);
        }
    }

    /**
     * Marker interface for plugins that support application-level
     * namespacing. When an {@code Operio} instance is built with an
     * {@link Builder#withAppName(String) appName}, it calls
     * {@link #setAppName(String)} on every registered service that
     * implements this interface.
     *
     * <p>Implementations should use the supplied name as a prefix or
     * namespace for any keys or identifiers they manage (e.g. Redis
     * key prefixes).
     */
    public interface AppNameAware {
        /**
         * Sets the application name used for namespacing.
         *
         * @param appName the application name, never {@code null}
         */
        void setAppName(String appName);
    }

    public static Builder builder() {
        return new Builder();
    }

    // =======================================================================
    // Read path
    // =======================================================================

    /**
     * High-level method to handle a data access request.
     *
     * <p>Resolves the caller's identity, discovers which fields in
     * {@code rawData} are sensitive, evaluates consent for each field,
     * logs the outcome, and returns a masked copy of the data with denied
     * fields redacted.
     *
     * @param rawData the data object to govern
     * @return a masked copy of the data with denied fields redacted
     */
    public Object access(Object rawData) {
        var identity = identityResolver.resolve();
        var scopes = sensitiveFieldResolver.resolveScopes(rawData);
        var response = consentService.evaluateConsent(identity.user(), scopes);
        for (ConsentScope scope : response.granted()) {
            auditLogger.logAccessGranted(identity.user(), scope);
        }
        for (ConsentScope scope : response.denied()) {
            auditLogger.logAccessDenied(
                identity.user(),
                scope,
                response.reasons().get(scope.fieldName())
            );
        }
        return maskingStrategy.mask(rawData, response.denied());
    }

    // =======================================================================
    // Write path — grant
    // =======================================================================

    /**
     * Grants consent for the given subject and scope.
     *
     * <p>If a {@link ConsentSyncService} is configured the grant is
     * dual-written (relational DB + cache). Otherwise the
     * {@link ConsentAdminService} is used for a DB-only write.
     *
     * @param subject the data subject identifier
     * @param scope   the consent scope (field-level or category-level)
     * @throws OperioConfigurationException if neither a sync service nor an admin
     *                                      service is configured
     */
    public void grantConsent(String subject, ConsentScope scope) {
        grantConsent(subject, scope, null);
    }

    /**
     * Grants consent with an expiration timestamp.
     *
     * @param subject   the data subject identifier
     * @param scope     the consent scope (field-level or category-level)
     * @param expiresAt when this consent grant expires, or {@code null}
     *                  for no expiration
     * @throws OperioConfigurationException if neither a sync service nor an admin
     *                                      service is configured
     */
    public void grantConsent(
        String subject,
        ConsentScope scope,
        Instant expiresAt
    ) {
        if (consentSyncService != null) {
            consentSyncService.syncGrant(subject, scope, expiresAt);
        } else if (consentAdminService != null) {
            consentAdminService.grantConsent(subject, scope, expiresAt);
        } else {
            throw new OperioConfigurationException(
                "No consent write provider configured. " +
                    "Register a ConsentSyncService (dual-write) or a " +
                    "ConsentAdminService (DB-only) via the builder."
            );
        }
    }

    // =======================================================================
    // Write path — revoke
    // =======================================================================

    /**
     * Revokes consent for the given subject and scope.
     *
     * <p>If a {@link ConsentSyncService} is configured the revocation is
     * dual-written (relational DB + cache). Otherwise the
     * {@link ConsentAdminService} is used for a DB-only write.
     *
     * @param subject the data subject identifier
     * @param scope   the consent scope (field-level or category-level)
     * @throws OperioConfigurationException if neither a sync service nor an admin
     *                                      service is configured
     */
    public void revokeConsent(String subject, ConsentScope scope) {
        revokeConsent(subject, scope, null);
    }

    /**
     * Revokes consent with an expiration timestamp.
     *
     * @param subject   the data subject identifier
     * @param scope     the consent scope (field-level or category-level)
     * @param expiresAt when this consent revocation expires, or {@code null}
     *                  for no expiration
     * @throws OperioConfigurationException if neither a sync service nor an admin
     *                                      service is configured
     */
    public void revokeConsent(
        String subject,
        ConsentScope scope,
        Instant expiresAt
    ) {
        if (consentSyncService != null) {
            consentSyncService.syncRevoke(subject, scope, expiresAt);
        } else if (consentAdminService != null) {
            consentAdminService.revokeConsent(subject, scope, expiresAt);
        } else {
            throw new OperioConfigurationException(
                "No consent write provider configured. " +
                    "Register a ConsentSyncService (dual-write) or a " +
                    "ConsentAdminService (DB-only) via the builder."
            );
        }
    }

    // =======================================================================
    // Accessors
    // =======================================================================

    /**
     * Returns the application name that namespaces all entities, fields,
     * and Redis keys managed by this instance.
     *
     * @return the application name, never {@code null}
     */
    public String appName() {
        return appName;
    }

    /**
     * Returns the configured {@link SensitiveDiscovery}, or {@code null}
     * if none was registered.
     */
    public SensitiveDiscovery<?> sensitiveDiscovery() {
        return sensitiveDiscovery;
    }

    /**
     * Returns the configured {@link ConsentDecisionService}, or {@code null}
     * if none was registered.
     */
    public ConsentDecisionService<?> consentService() {
        return consentService;
    }

    /**
     * Returns the configured {@link ConsentAdminService}, or {@code null}
     * if none was registered.
     */
    public ConsentAdminService<?> consentAdminService() {
        return consentAdminService;
    }

    /**
     * Returns the configured {@link ConsentSyncService}, or {@code null}
     * if none was registered.
     */
    public ConsentSyncService<?> consentSyncService() {
        return consentSyncService;
    }

    /**
     * Returns {@code true} if a consent write provider (sync or admin) is
     * available.
     */
    public boolean hasConsentWriteProvider() {
        return consentSyncService != null || consentAdminService != null;
    }

    // =======================================================================
    // Discovery → Registration pipeline
    // =======================================================================

    /**
     * Runs the configured {@link SensitiveDiscovery} plugin to scan for
     * sensitive fields and categories, then registers every discovered item
     * into the consent admin schema via the configured
     * {@link ConsentAdminService}.
     *
     * <p>This method orchestrates the full pipeline:
     * <ol>
     *   <li>Calls {@link SensitiveDiscovery#discover()} to obtain a
     *       {@link DiscoveryResult}.</li>
     *   <li>Registers each discovered category via
     *       {@link ConsentAdminService#registerCategory}.</li>
     *   <li>Registers each discovered field via
     *       {@link ConsentAdminService#registerField}.</li>
     *   <li>Links each field to its category (when present) via
     *       {@link ConsentAdminService#linkFieldToCategory}.</li>
     * </ol>
     *
     * <p>All registration methods use <strong>upsert / idempotent</strong>
     * semantics, so calling this method multiple times is safe — existing
     * rows are updated rather than duplicated.
     *
     * @return the {@link DiscoveryResult} that was discovered and registered
     * @throws OperioConfigurationException if no {@code SensitiveDiscovery}
     *         or no {@code ConsentAdminService} is configured
     */
    public DiscoveryResult syncDiscoveredFields() {
        if (sensitiveDiscovery == null) {
            throw new OperioConfigurationException(
                "No SensitiveDiscovery plugin configured. Wire one via " +
                    "withSensitiveDiscovery() on the builder."
            );
        }

        // Resolve the admin service — prefer the sync service's embedded
        // admin (if it exposes one in the future), otherwise fall back to
        // the directly wired admin service.
        ConsentAdminService<?> admin = consentAdminService;
        if (admin == null) {
            throw new OperioConfigurationException(
                "No ConsentAdminService configured. Schema registration " +
                    "requires a consent admin provider (e.g. jOOQ)."
            );
        }

        // 1. Discover
        DiscoveryResult result = sensitiveDiscovery.discover();
        LOG.info(
            () ->
                "SensitiveDiscovery found " +
                result.fields().size() +
                " field(s) and " +
                result.categories().size() +
                " category/ies"
        );

        // 2. Register categories first (fields may reference them)
        for (String category : result.categories()) {
            admin.registerCategory(category, null);
            LOG.fine(() -> "Registered category: " + category);
        }

        // 3. Register fields (upserts entity if entityName is present)
        for (DiscoveredField field : result.fields()) {
            admin.registerField(
                field.fieldName(),
                field.entityName(),
                field.description()
            );
            LOG.fine(
                () ->
                    "Registered field: " +
                    field.fieldName() +
                    " (entity=" +
                    field.entityName() +
                    ")"
            );
        }

        // 4. Link fields to categories
        for (DiscoveredField field : result.fields()) {
            if (field.category() != null && !field.category().isBlank()) {
                admin.linkFieldToCategory(
                    field.fieldName(),
                    field.entityName(),
                    field.category()
                );
                LOG.fine(
                    () ->
                        "Linked field '" +
                        field.fieldName() +
                        "' → category '" +
                        field.category() +
                        "'"
                );
            }
        }

        return result;
    }

    // =======================================================================
    // Health check
    // =======================================================================

    /**
     * Verifies that all configured plugins are ready.
     *
     * <p>Calls {@link Plugin#isReady()} on every registered plugin and throws
     * {@link OperioConfigurationException} if any returns {@code false}.
     * Intended to be called at application startup (e.g. in a Spring
     * {@code @PostConstruct} or health-check endpoint).
     *
     * @throws OperioConfigurationException if any plugin is not ready, with a
     *         message listing the failing plugin names
     */
    public void verifyReady() {
        List<String> notReady = new ArrayList<>();
        checkReady(consentService, notReady);
        checkReady(consentAdminService, notReady);
        checkReady(consentSyncService, notReady);
        checkReady(identityResolver, notReady);
        checkReady(auditLogger, notReady);
        checkReady(maskingStrategy, notReady);
        checkReady(sensitiveFieldResolver, notReady);
        checkReady(sensitiveDiscovery, notReady);
        if (!notReady.isEmpty()) {
            throw new OperioConfigurationException(
                "The following plugins are not ready: " +
                    String.join(", ", notReady)
            );
        }
    }

    private static void checkReady(Object plugin, List<String> notReady) {
        if (plugin instanceof Plugin<?> p && !p.isReady()) {
            notReady.add(p.getName());
        }
    }

    // =======================================================================
    // Builder
    // =======================================================================

    public static class Builder {

        // -- Instance identity ------------------------------------------------
        private String appName;

        // -- Read-path --------------------------------------------------------
        private ConsentDecisionService<?> consentService;
        private IdentityResolver<?> identityResolver;
        private AuditLogger auditLogger;
        private MaskingStrategy<?> maskingStrategy;
        private SensitiveFieldResolver<?> sensitiveFieldResolver;

        // -- Write-path -------------------------------------------------------
        private ConsentAdminService<?> consentAdminService;
        private ConsentSyncService<?> consentSyncService;

        // -- Discovery --------------------------------------------------------
        private SensitiveDiscovery<?> sensitiveDiscovery;

        // -- Health check -----------------------------------------------------
        private boolean verifyOnBuild = false;

        public Operio build() {
            Operio operio = new Operio(this);
            if (verifyOnBuild) {
                operio.verifyReady();
            }
            return operio;
        }

        /**
         * Sets the application name that namespaces all entities, fields,
         * and Redis keys managed by this {@code Operio} instance.
         *
         * <p>Defaults to {@value Operio#DEFAULT_APP_NAME} if not set. The
         * name is propagated at build time to every registered service that
         * implements {@link AppNameAware}.
         *
         * @param appName the application name (e.g. {@code "my-healthcare-app"})
         * @return this builder
         */
        public Builder withAppName(String appName) {
            this.appName = appName;
            return this;
        }

        /**
         * When enabled, {@link Operio#verifyReady()} is called automatically at
         * the end of {@link #build()}, failing fast if any plugin is unhealthy.
         */
        public Builder verifyOnBuild() {
            this.verifyOnBuild = true;
            return this;
        }

        /**
         * Applies a {@link PluginBundle} to this builder, wiring all plugins the
         * bundle provides.
         *
         * <p>Multiple bundles can be applied. If two bundles set the same plugin
         * slot, the later bundle wins. Individual {@code withXxx} calls after a
         * bundle also override.
         *
         * @param bundle the bundle to apply
         * @return this builder
         */
        public Builder withBundle(PluginBundle bundle) {
            bundle.apply(this);
            return this;
        }

        // -- Read-path wiring -------------------------------------------------

        /**
         * Resolves a {@link ConsentDecisionService} by name via
         * {@link PluginRegistry} (no configuration).
         */
        public Builder withConsentProvider(String name) {
            this.consentService = PluginRegistry.resolve(
                ConsentDecisionService.class,
                name
            );
            return this;
        }

        /**
         * Resolves and configures a {@link ConsentDecisionService} by name.
         */
        public <C extends PluginConfig> Builder withConsentProvider(
            String name,
            C config
        ) {
            this.consentService = PluginRegistry.resolve(
                ConsentDecisionService.class,
                name,
                config
            );
            return this;
        }

        /**
         * Registers a pre-built {@link ConsentDecisionService} instance.
         *
         * <p>This overload bypasses the {@link PluginRegistry} and uses the
         * given instance directly. It is intended for bundles and advanced
         * callers that need to share a single service instance across
         * multiple builder slots (e.g. sharing an in-memory cache between
         * the sync layer and the read path).
         *
         * @param service the pre-built consent decision service
         * @return this builder
         */
        public Builder withConsentProvider(ConsentDecisionService<?> service) {
            this.consentService = service;
            return this;
        }

        public Builder withIdentityProvider(String name) {
            this.identityResolver = PluginRegistry.resolve(
                IdentityResolver.class,
                name
            );
            return this;
        }

        public <C extends PluginConfig> Builder withIdentityProvider(
            String name,
            C config
        ) {
            this.identityResolver = PluginRegistry.resolve(
                IdentityResolver.class,
                name,
                config
            );
            return this;
        }

        public Builder withAuditProvider(String name) {
            this.auditLogger = PluginRegistry.resolve(AuditLogger.class, name);
            return this;
        }

        public <C extends PluginConfig> Builder withAuditProvider(
            String name,
            C config
        ) {
            this.auditLogger = PluginRegistry.resolve(
                AuditLogger.class,
                name,
                config
            );
            return this;
        }

        public Builder withMaskingProvider(String name) {
            this.maskingStrategy = PluginRegistry.resolve(
                MaskingStrategy.class,
                name
            );
            return this;
        }

        public <C extends PluginConfig> Builder withMaskingProvider(
            String name,
            C config
        ) {
            this.maskingStrategy = PluginRegistry.resolve(
                MaskingStrategy.class,
                name,
                config
            );
            return this;
        }

        public Builder withSensitiveFieldResolver(String name) {
            this.sensitiveFieldResolver = PluginRegistry.resolve(
                SensitiveFieldResolver.class,
                name
            );
            return this;
        }

        public <C extends PluginConfig> Builder withSensitiveFieldResolver(
            String name,
            C config
        ) {
            this.sensitiveFieldResolver = PluginRegistry.resolve(
                SensitiveFieldResolver.class,
                name,
                config
            );
            return this;
        }

        // -- Write-path wiring ------------------------------------------------

        /**
         * Resolves a {@link ConsentAdminService} by name via
         * {@link PluginRegistry} (no configuration).
         *
         * <p>Used for DB-only consent writes. If a
         * {@link ConsentSyncService} is also registered it takes precedence
         * for write operations.
         */
        public Builder withConsentAdminProvider(String name) {
            this.consentAdminService = PluginRegistry.resolve(
                ConsentAdminService.class,
                name
            );
            return this;
        }

        /**
         * Resolves and configures a {@link ConsentAdminService} by name.
         */
        public <C extends PluginConfig> Builder withConsentAdminProvider(
            String name,
            C config
        ) {
            this.consentAdminService = PluginRegistry.resolve(
                ConsentAdminService.class,
                name,
                config
            );
            return this;
        }

        /**
         * Registers a pre-built {@link ConsentAdminService} instance.
         *
         * <p>This overload bypasses the {@link PluginRegistry} and uses the
         * given instance directly. It is intended for bundles that construct
         * the admin service internally and need to share the same instance
         * with a sync service (e.g. the embedded bundle shares a single
         * jOOQ admin between the sync layer and the schema-registration
         * path).
         *
         * @param service the pre-built consent admin service
         * @return this builder
         */
        public Builder withConsentAdminProvider(
            ConsentAdminService<?> service
        ) {
            this.consentAdminService = service;
            return this;
        }

        /**
         * Resolves a {@link ConsentSyncService} by name via
         * {@link PluginRegistry} (no configuration).
         *
         * <p>Used for dual-write consent operations (relational DB + cache).
         * When registered, this service takes precedence over any
         * {@link ConsentAdminService} for write operations.
         */
        public Builder withConsentSyncProvider(String name) {
            this.consentSyncService = PluginRegistry.resolve(
                ConsentSyncService.class,
                name
            );
            return this;
        }

        /**
         * Resolves and configures a {@link ConsentSyncService} by name.
         */
        public <C extends PluginConfig> Builder withConsentSyncProvider(
            String name,
            C config
        ) {
            this.consentSyncService = PluginRegistry.resolve(
                ConsentSyncService.class,
                name,
                config
            );
            return this;
        }

        /**
         * Registers a pre-built {@link ConsentSyncService} instance.
         *
         * <p>This overload bypasses the {@link PluginRegistry} and uses the
         * given instance directly. It is intended for bundles that
         * construct the sync service internally and extract its child
         * services (admin, decision) for shared wiring — for example, the
         * embedded bundle creates the sync service first and then reuses
         * its internal jOOQ admin and Caffeine decision service instances
         * as the builder's admin and consent providers.
         *
         * @param service the pre-built consent sync service
         * @return this builder
         */
        public Builder withConsentSyncProvider(ConsentSyncService<?> service) {
            this.consentSyncService = service;
            return this;
        }

        // -- Discovery wiring -------------------------------------------------

        /**
         * Resolves a {@link SensitiveDiscovery} plugin by name via
         * {@link PluginRegistry} (no configuration).
         *
         * <p>Used with {@link Operio#syncDiscoveredFields()} to scan a
         * codebase for sensitive fields and automatically register them
         * in the consent admin schema.
         */
        public Builder withSensitiveDiscovery(String name) {
            this.sensitiveDiscovery = PluginRegistry.resolve(
                SensitiveDiscovery.class,
                name
            );
            return this;
        }

        /**
         * Resolves and configures a {@link SensitiveDiscovery} plugin by name.
         */
        public <C extends PluginConfig> Builder withSensitiveDiscovery(
            String name,
            C config
        ) {
            this.sensitiveDiscovery = PluginRegistry.resolve(
                SensitiveDiscovery.class,
                name,
                config
            );
            return this;
        }
    }
}
