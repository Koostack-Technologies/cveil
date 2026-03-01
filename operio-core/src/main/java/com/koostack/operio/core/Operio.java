package com.koostack.operio.core;

import com.koostack.operio.core.exception.OperioConfigurationException;
import com.koostack.operio.core.registry.PluginRegistry;
import com.koostack.operio.core.service.AuditLogger;
import com.koostack.operio.core.service.ConsentDecisionService;
import com.koostack.operio.core.sdk.IdentityResolver;
import com.koostack.operio.core.service.MaskingStrategy;
import com.koostack.operio.core.service.PlatformAdminService;
import com.koostack.operio.core.sdk.SensitiveDiscovery;
import com.koostack.operio.core.service.SensitiveFieldResolver;
import com.koostack.operio.core.platform.billing.BillingCommandService;
import com.koostack.operio.core.platform.billing.BillingQueryService;
import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.core.CoreQueryService;
import com.koostack.operio.core.platform.iam.IdpProvider;
import com.koostack.operio.core.platform.iam.TokenValidationService;
import com.koostack.operio.core.tenant.authz.AuthzProvider;
import com.koostack.operio.core.spi.Plugin;
import com.koostack.operio.core.spi.PluginBundle;
import com.koostack.operio.core.spi.PluginConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Abstract base class for Operio application services.
 *
 * <p>Provides the {@link Builder} and plugin-wiring infrastructure shared
 * by all concrete service implementations ({@link TenantService},
 * {@link PlatformService}). Manages plugin lifecycle, namespacing, and
 * health checks.
 *
 * <h2>Construction</h2>
 * <p>Use {@link #builder()} to obtain a {@link Builder}, wire up the desired
 * providers, then call {@link Builder#build()} to obtain a concrete
 * service implementation (typically via a bundle).
 */
public abstract class Operio implements OperioService {

    private static final Logger LOG = Logger.getLogger(Operio.class.getName());

    /** Default application name used when none is explicitly configured. */
    public static final String DEFAULT_APP_NAME = "operio";

    // -- Instance identity ----------------------------------------------------
    private final String appName;

    // -- Read-path plugins ----------------------------------------------------
    private final ConsentDecisionService<?> consentService;
    private final IdentityResolver<?> identityResolver;
    private final AuditLogger<?> auditLogger;
    private final MaskingStrategy<?> maskingStrategy;
    private final SensitiveFieldResolver<?> sensitiveFieldResolver;

    // -- Platform plugins -----------------------------------------------------
    private final TokenValidationService<?> tokenValidationService;
    private final PlatformAdminService<?> platformAdminService;
    private final IdpProvider<?> idpProvider;
    private final CoreCommandService<?> coreCommandService;
    private final CoreQueryService<?> coreQueryService;
    private final BillingCommandService<?> billingCommandService;
    private final BillingQueryService<?> billingQueryService;

    // -- Tenant plugins -------------------------------------------------------
    private final AuthzProvider<?> authzProvider;

    // -- Discovery plugins ----------------------------------------------------
    private final SensitiveDiscovery<?> sensitiveDiscovery;

    protected Operio(Builder builder) {
        this.appName =
            builder.appName != null ? builder.appName : DEFAULT_APP_NAME;
        this.consentService = builder.consentService;
        this.identityResolver = builder.identityResolver;
        this.auditLogger = builder.auditLogger;
        this.maskingStrategy = builder.maskingStrategy;
        this.sensitiveFieldResolver = builder.sensitiveFieldResolver;
        this.tokenValidationService = builder.tokenValidationService;
        this.platformAdminService = builder.platformAdminService;
        this.idpProvider = builder.idpProvider;
        this.coreCommandService = builder.coreCommandService;
        this.coreQueryService = builder.coreQueryService;
        this.billingCommandService = builder.billingCommandService;
        this.billingQueryService = builder.billingQueryService;
        this.authzProvider = builder.authzProvider;
        this.sensitiveDiscovery = builder.sensitiveDiscovery;

        // Propagate appName to any service that supports namespacing
        propagateAppName(consentService);
        propagateAppName(platformAdminService);
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
    // OperioService
    // =======================================================================

    @Override
    public String appName() {
        return appName;
    }

    @Override
    public boolean isReady() {
        try {
            verifyReady();
            return true;
        } catch (OperioConfigurationException e) {
            return false;
        }
    }

    // =======================================================================
    // Accessors — read-path plugins
    // =======================================================================

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
     * Returns the configured {@link TokenValidationService}, or {@code null}
     * if none was registered.
     */
    public TokenValidationService<?> tokenValidationService() {
        return tokenValidationService;
    }

    /**
     * Returns the configured {@link PlatformAdminService}, or {@code null}
     * if none was registered.
     */
    public PlatformAdminService<?> platformAdminService() {
        return platformAdminService;
    }

    /**
     * Returns the configured {@link IdpProvider}, or {@code null}
     * if none was registered.
     */
    public IdpProvider<?> idpProvider() {
        return idpProvider;
    }

    /** Returns the configured {@link CoreCommandService}, or {@code null}. */
    public CoreCommandService<?> coreCommandService() {
        return coreCommandService;
    }

    /** Returns the configured {@link CoreQueryService}, or {@code null}. */
    public CoreQueryService<?> coreQueryService() {
        return coreQueryService;
    }

    /** Returns the configured {@link BillingCommandService}, or {@code null}. */
    public BillingCommandService<?> billingCommandService() {
        return billingCommandService;
    }

    /** Returns the configured {@link BillingQueryService}, or {@code null}. */
    public BillingQueryService<?> billingQueryService() {
        return billingQueryService;
    }

    /**
     * Returns the configured {@link AuthzProvider}, or {@code null}
     * if none was registered.
     */
    public AuthzProvider<?> authzProvider() {
        return authzProvider;
    }

    /**
     * Returns the configured {@link IdentityResolver}, or {@code null}
     * if none was registered.
     */
    public IdentityResolver<?> identityResolver() {
        return identityResolver;
    }

    /**
     * Returns the configured {@link MaskingStrategy}, or {@code null}
     * if none was registered.
     */
    public MaskingStrategy<?> maskingStrategy() {
        return maskingStrategy;
    }

    /**
     * Returns the configured {@link AuditLogger}, or {@code null}
     * if none was registered.
     */
    public AuditLogger<?> auditLogger() {
        return auditLogger;
    }

    /**
     * Returns the configured {@link SensitiveFieldResolver}, or {@code null}
     * if none was registered.
     */
    public SensitiveFieldResolver<?> sensitiveFieldResolver() {
        return sensitiveFieldResolver;
    }

    // =======================================================================
    // Health check
    // =======================================================================

    /**
     * Verifies that all configured plugins are ready.
     *
     * @throws OperioConfigurationException if any plugin is not ready
     */
    @Override
    public void verifyReady() {
        List<String> notReady = new ArrayList<>();
        checkReady(consentService, notReady);
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

        // -- Platform ---------------------------------------------------------
        private TokenValidationService<?> tokenValidationService;
        private PlatformAdminService<?> platformAdminService;
        private IdpProvider<?> idpProvider;
        private CoreCommandService<?> coreCommandService;
        private CoreQueryService<?> coreQueryService;
        private BillingCommandService<?> billingCommandService;
        private BillingQueryService<?> billingQueryService;

        // -- Tenant -----------------------------------------------------------
        private AuthzProvider<?> authzProvider;

        // -- Discovery --------------------------------------------------------
        private SensitiveDiscovery<?> sensitiveDiscovery;

        // -- Health check -----------------------------------------------------
        private boolean verifyOnBuild = false;

        /** Returns this builder's appName (used by subclass build methods). */
        public String appName() { return appName; }

        /**
         * Builds a {@link DefaultTenantService} from this builder's configuration.
         *
         * @param commandService the consent admin command service
         * @param queryService   the consent admin query service
         * @return a fully-wired DefaultTenantService
         */
        public DefaultTenantService buildTenantService(
            com.koostack.operio.core.tenant.consent.ConsentAdminCommandService<?> commandService,
            com.koostack.operio.core.tenant.consent.ConsentAdminQueryService<?> queryService
        ) {
            DefaultTenantService service = new DefaultTenantService(this, commandService, queryService);
            if (verifyOnBuild) {
                service.verifyReady();
            }
            return service;
        }

        /**
         * Builds a {@link DefaultPlatformService} from this builder's configuration.
         * Uses the registered IdpProvider, CoreCommandService, and BillingCommandService.
         *
         * @return a fully-wired DefaultPlatformService
         */
        public DefaultPlatformService buildPlatformService() {
            DefaultPlatformService service = new DefaultPlatformService(
                appName, idpProvider, coreCommandService, billingCommandService);
            if (verifyOnBuild) {
                service.verifyReady();
            }
            return service;
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

        // -- Platform wiring --------------------------------------------------

        /**
         * Resolves a {@link TokenValidationService} by name via
         * {@link PluginRegistry} (no configuration).
         */
        public Builder withTokenValidationService(String name) {
            this.tokenValidationService = PluginRegistry.resolve(
                TokenValidationService.class,
                name
            );
            return this;
        }

        /**
         * Resolves and configures a {@link TokenValidationService} by name.
         */
        public <C extends PluginConfig> Builder withTokenValidationService(
            String name,
            C config
        ) {
            this.tokenValidationService = PluginRegistry.resolve(
                TokenValidationService.class,
                name,
                config
            );
            return this;
        }

        /**
         * Registers a pre-built {@link TokenValidationService} instance.
         *
         * @param service the pre-built token validation service
         * @return this builder
         */
        public Builder withTokenValidationService(TokenValidationService<?> service) {
            this.tokenValidationService = service;
            return this;
        }

        /**
         * Resolves a {@link PlatformAdminService} by name via
         * {@link PluginRegistry} (no configuration).
         */
        public Builder withPlatformAdmin(String name) {
            this.platformAdminService = PluginRegistry.resolve(
                PlatformAdminService.class,
                name
            );
            return this;
        }

        /**
         * Resolves and configures a {@link PlatformAdminService} by name.
         */
        public <C extends PluginConfig> Builder withPlatformAdmin(
            String name,
            C config
        ) {
            this.platformAdminService = PluginRegistry.resolve(
                PlatformAdminService.class,
                name,
                config
            );
            return this;
        }

        /**
         * Registers a pre-built {@link PlatformAdminService} instance.
         *
         * @param service the pre-built platform admin service
         * @return this builder
         */
        public Builder withPlatformAdmin(PlatformAdminService<?> service) {
            this.platformAdminService = service;
            return this;
        }

        /**
         * Registers a pre-built {@link IdpProvider} instance for identity
         * management (user registration, token verification, tenant lifecycle).
         *
         * @param idpProvider the identity provider
         * @return this builder
         */
        public Builder withIdpProvider(IdpProvider<?> idpProvider) {
            this.idpProvider = idpProvider;
            return this;
        }

        /**
         * Registers a pre-built {@link CoreCommandService} instance.
         */
        public Builder withCoreCommandService(CoreCommandService<?> service) {
            this.coreCommandService = service;
            return this;
        }

        /**
         * Registers a pre-built {@link CoreQueryService} instance.
         */
        public Builder withCoreQueryService(CoreQueryService<?> service) {
            this.coreQueryService = service;
            return this;
        }

        /**
         * Registers a pre-built {@link BillingCommandService} instance.
         */
        public Builder withBillingCommandService(BillingCommandService<?> service) {
            this.billingCommandService = service;
            return this;
        }

        /**
         * Registers a pre-built {@link BillingQueryService} instance.
         */
        public Builder withBillingQueryService(BillingQueryService<?> service) {
            this.billingQueryService = service;
            return this;
        }

        /**
         * Registers a pre-built {@link AuthzProvider} instance for
         * tenant-scoped authorization (role management).
         *
         * @param authzProvider the authorization provider
         * @return this builder
         */
        public Builder withAuthzProvider(AuthzProvider<?> authzProvider) {
            this.authzProvider = authzProvider;
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
