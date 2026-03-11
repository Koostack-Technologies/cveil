package com.koostack.operio.rest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.koostack.operio.bundle.core.CoreConsentBundle;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.cqrs.CommandDispatcher;
import com.koostack.operio.core.cqrs.QueryDispatcher;
import com.koostack.operio.core.platform.billing.BillingCommandService;
import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.core.CoreQueryService;
import com.koostack.operio.core.platform.iam.IdpProvider;
import com.koostack.operio.core.platform.iam.IdpTenantResolver;
import com.koostack.operio.core.platform.iam.TokenValidationService;
import com.koostack.operio.core.spi.PluginBundle;
import com.koostack.operio.core.tenant.authz.AuthzProvider;
import com.koostack.operio.core.tenant.consent.invalidation.SubscriptionManager;
import com.koostack.operio.platform.billing.jooq.BillingProjectionHandler;
import com.koostack.operio.platform.billing.jooq.BillingQueryDispatcher;
import com.koostack.operio.platform.billing.jooq.JooqBillingCommandConfig;
import com.koostack.operio.platform.billing.jooq.JooqBillingDispatcher;
import com.koostack.operio.platform.billing.jooq.JooqBillingQueryConfig;
import com.koostack.operio.platform.core.jooq.CoreProjectionHandler;
import com.koostack.operio.platform.core.jooq.CoreQueryDispatcher;
import com.koostack.operio.platform.core.jooq.JooqCoreCommandConfig;
import com.koostack.operio.platform.core.jooq.JooqCoreDispatcher;
import com.koostack.operio.platform.core.jooq.JooqCoreQueryConfig;
// TenantManager imports removed — provisioning pipeline eliminated (Phase 8)
import com.koostack.operio.plugin.consent.jooq.ConsentAdminCommandConfig;
import com.koostack.operio.plugin.consent.jooq.CountSubjectCacheKeysQueryActuator;
import com.koostack.operio.plugin.consent.jooq.DeleteSubjectCacheKeysQueryActuator;
import com.koostack.operio.plugin.consent.jooq.EvaluateConsentQueryActuator;
import com.koostack.operio.plugin.consent.jooq.GetConsentGraphQueryActuator;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminCommandService;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminQueryDispatcher;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig;
import com.koostack.operio.plugin.event.jooq.JooqPlatformEventService;
import com.koostack.operio.plugin.event.jooq.PlatformEventJooqConfig;
import com.koostack.operio.plugin.event.jooq.RedisEventPublisher;
// TenantRegistry import removed — no async tenant discovery needed (Phase 8)
import com.koostack.operio.plugin.event.jooq.ShardedStreamConsumer;
import com.koostack.operio.plugin.jooq.common.JooqDataSourceFactory;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisClientFactory;
import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider;
import com.koostack.operio.plugin.redis.common.pubsub.RedisInvalidationPublisher;
import com.koostack.operio.plugin.redis.common.pubsub.RedisInvalidationSubscriber;
import com.koostack.operio.plugin.redis.common.stream.AbstractStreamConsumer;
import com.koostack.operio.plugin.redis.common.stream.StreamHandler;
import com.koostack.operio.tenant.consent.projection.admin.ConsentAdminProjectionHandler;
import com.koostack.operio.tenant.consent.projection.decision.ConsentGraphProjectionHandler;
import com.koostack.operio.tenant.consent.projection.decision.ConsentGraphRedisRepository;
// Tenant event service imports removed — unified event store handles all events (Phase 8)
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

/**
 * CDI producer that wires the {@link Operio} instance and its key sub-services from Quarkus
 * configuration.
 *
 * <p>This is the Quarkus equivalent of the Spring Boot {@code OperioAutoConfiguration} in the
 * starter module. It reads {@link OperioConfig} (SmallRye {@code @ConfigMapping}) and constructs
 * the appropriate consent bundle, platform admin service, and token verifier based on the
 * configured deployment mode.
 *
 * <h2>Production mode ({@code operio.mode=production})</h2>
 *
 * <ul>
 *   <li>{@link CoreConsentBundle} — Postgres (jOOQ) + Redis consent stack with dual-write sync
 *   <li>CQRS command/query services — tenant lifecycle, API key management, billing, and plan
 *       management
 *   <li>{@code ApiKeyTokenVerifier} — SHA-256 bearer token verification against the {@code
 *       platform_iam.api_key} projection table
 * </ul>
 *
 * <h2>Embedded mode ({@code operio.mode=embedded})</h2>
 *
 * <ul>
 *   <li>{@link EmbeddedConsentBundle} — H2 in-memory + Caffeine cache, zero external infrastructure
 *       (ideal for local dev and testing)
 *   <li>Platform CQRS services and token verifier are <strong>not</strong> wired — embedded mode
 *       has no multi-tenant management
 * </ul>
 *
 * <h2>Produced beans</h2>
 *
 * <ul>
 *   <li>{@link Operio} — the primary entry point for consent operations
 *   <li>{@link CoreQueryService}, {@link CoreCommandService}, etc. — CQRS platform services
 *       (production only; {@code null} in embedded mode)
 *   <li>{@link TokenValidationService} — bearer token verification (production only; {@code null}
 *       in embedded mode)
 * </ul>
 *
 * <p>All three beans are {@code @ApplicationScoped} singletons — created once at startup and shared
 * across all request threads.
 *
 * @see OperioConfig
 * @see CoreConsentBundle
 * @see EmbeddedConsentBundle
 */
@ApplicationScoped
public class OperioProducers {

    private static final Logger LOG = LoggerFactory.getLogger(OperioProducers.class);

    private static final String MODE_PRODUCTION = "production";
    private static final String MODE_EMBEDDED = "embedded";

    // -- Platform CQRS services (set during tenantService() production init) --
    @SuppressWarnings("rawtypes")
    private CoreCommandService coreCommandService;

    @SuppressWarnings("rawtypes")
    private BillingCommandService billingCommandService;

    @SuppressWarnings("rawtypes")
    private CoreQueryService coreQueryService;

    @SuppressWarnings("rawtypes")
    private com.koostack.operio.core.platform.plan.PlanQueryService planQueryService;

    private com.koostack.operio.core.cqrs.CommandDispatcher iamDispatcher;

    @SuppressWarnings("rawtypes")
    private com.koostack.operio.core.platform.iam.IamQueryService iamQueryService;

    /** Platform event service — unified event store for all events. */
    private JooqPlatformEventService platformEventService;

    /** Keycloak admin client built during CQRS wiring — shared across projections. */
    private org.keycloak.admin.client.Keycloak keycloakAdminClient;

    /** Consent command service — stored for CDI exposure as CommandDispatcher. */
    private JooqConsentAdminCommandService consentAdminCommandService;

    /** Consent query dispatcher — stored for CDI exposure as QueryDispatcher. */
    private JooqConsentAdminQueryDispatcher consentAdminQueryDispatcher;

    /** Health-checkable plugins collected during tenantService() init. */
    private java.util.List<com.koostack.operio.core.spi.Plugin<?>> healthPlugins;

    @Inject OperioConfig config;

    @SuppressWarnings("rawtypes")
    @Inject
    Instance<IdpProvider> idpProviderInstance;

    @Inject Instance<AuthzProvider<?>> authzProviderInstance;

    // -----------------------------------------------------------------------
    // Primary Operio bean
    // -----------------------------------------------------------------------

    /**
     * Produces the {@link Operio} singleton — the primary entry point for all consent operations
     * (grant, revoke, evaluate, batch, access, discovery sync, health checks).
     *
     * <p>Wiring logic:
     *
     * <ol>
     *   <li>Selects the consent bundle based on {@code operio.mode}.
     *   <li>In production mode, also wires the platform admin service and token verifier from
     *       {@code operio.platform-db} and {@code operio.redis} config.
     *   <li>Sets the application name if configured.
     * </ol>
     *
     * @return the fully-wired {@link Operio} instance (used for CDI initialization ordering)
     * @throws IllegalStateException if required configuration is missing for the selected mode
     */
    @Produces
    @ApplicationScoped
    public Operio operioInit() {
        String mode = config.mode();
        LOG.info("Operio REST API: initializing (mode={})", mode);

        // Validate platform DB credentials upfront in production mode so
        // misconfiguration is caught before any connections are attempted.
        if (MODE_PRODUCTION.equalsIgnoreCase(mode)) {
            validatePlatformDbCredentials();
        }

        Operio.Builder builder = Operio.builder();

        // -- Consent bundle (wires read-path: Redis decision service) ---------
        PluginBundle consentBundle = createConsentBundle(mode);
        builder.withBundle(consentBundle);

        // -- Application name (override after bundle so config wins) ----------
        config.appName()
                .ifPresent(
                        appName -> {
                            if (!appName.isBlank()) {
                                LOG.info("Operio REST API: app-name={}", appName);
                                builder.withAppName(appName);
                            }
                        });

        // -- Token verifier removed (API keys deprecated, JWT used instead) ---
        LOG.info("Operio REST API: JWT-only auth — no API key token verifier needed");

        // -- IdP provider (production only, when Keycloak is configured) ------
        if (idpProviderInstance.isResolvable()) {
            IdpProvider<?> idp = idpProviderInstance.get();
            if (idp != null && idp.isAvailable()) {
                builder.withIdpProvider(idp);
                LOG.info("Operio REST API: IdpProvider wired into builder");
            }
        }

        // -- Authz provider (production only, when Keycloak is configured) ----
        if (authzProviderInstance.isResolvable()) {
            AuthzProvider<?> authz = authzProviderInstance.get();
            if (authz != null) {
                builder.withAuthzProvider(authz);
                LOG.info("Operio REST API: AuthzProvider wired into builder");
            }
        }

        // -- Command + query services (production: event-sourced + jOOQ) ------
        JooqConsentAdminCommandService commandService = new JooqConsentAdminCommandService();
        this.consentAdminCommandService = commandService;
        JooqConsentAdminQueryDispatcher queryService = new JooqConsentAdminQueryDispatcher();
        this.consentAdminQueryDispatcher = queryService;

        // -- Command + query services (production: event-sourced + jOOQ) ------
        // Graph replaces Redis admin cache — JOOQ admin query used directly
        com.koostack.operio.core.tenant.consent.ConsentAdminQueryService<?> effectiveQueryService =
                queryService;

        // DSAR service and key encryptor are wired into the command config below;
        // declared here so they're available after wirePlatformCqrsServices().
        com.koostack.operio.plugin.consent.jooq.JooqSubjectDsarService dsarService = null;
        java.util.function.UnaryOperator<String> keyEncryptor = null;

        if (MODE_PRODUCTION.equalsIgnoreCase(mode)
                && consentBundle instanceof CoreConsentBundle coreBundle) {
            // Consent query service wired from the bundle's jOOQ config.
            JooqConsentAdminServiceConfig jooqConfig = coreBundle.jooqConfig();

            // -- SubjectDsarService + key encryptor (used by command actuators) --
            dsarService = new com.koostack.operio.plugin.consent.jooq.JooqSubjectDsarService();
            dsarService.configure(jooqConfig);

            if (config.platformDb().isPresent()) {
                String encKey =
                        config.platformDb()
                                .get()
                                .encryptionKey()
                                .filter(k -> !k.isBlank())
                                .orElse(null);
                if (encKey != null) {
                    final String capturedKey = encKey;
                    keyEncryptor =
                            raw ->
                                    com.koostack.operio.plugin.platform.jooq.crypto.FieldEncryptor
                                            .encrypt(raw, capturedKey);
                }
            }

            queryService.configure(jooqConfig);

            LOG.info("Operio REST API: consent query service (jOOQ) wired");
        }

        // -- Platform CQRS services (production only) -------------------------
        if (MODE_PRODUCTION.equalsIgnoreCase(mode)) {
            wirePlatformCqrsServices(builder);
        }

        // -- Configure consent commands with unified platform event service ---
        // Deferred until after wirePlatformCqrsServices() because consent commands
        // write events to the unified operio_event.event table via platformEventService.
        if (this.platformEventService != null) {
            commandService.configure(
                    new ConsentAdminCommandConfig(
                            this.platformEventService, dsarService, keyEncryptor));
            LOG.info(
                    "Operio REST API: consent command service configured with unified event"
                            + " service");
        }

        // Propagate appName to consent service (was previously done inside Operio constructor)
        var consentDecisionService = builder.consentService();
        if (consentDecisionService instanceof Operio.AppNameAware appNameAware) {
            appNameAware.setAppName(
                    builder.appName() != null ? builder.appName() : Operio.DEFAULT_APP_NAME);
        }

        // Store health-checkable plugins for OperioHealthService producer
        this.healthPlugins = new java.util.ArrayList<>();
        if (consentDecisionService instanceof com.koostack.operio.core.spi.Plugin<?> p) {
            this.healthPlugins.add(p);
        }

        // Register Redis-backed query actuators on the consent query dispatcher.
        // ConsentDecisionService and ConsentGraphQueryService come from the Builder
        // (populated by CoreConsentBundle during apply()).
        if (consentDecisionService != null
                && queryService instanceof JooqConsentAdminQueryDispatcher qd) {
            qd.registerActuator(
                    EvaluateConsentQueryActuator.EvaluateConsentQuery.class,
                    new EvaluateConsentQueryActuator(consentDecisionService));
            qd.registerActuator(
                    CountSubjectCacheKeysQueryActuator.CountSubjectCacheKeysQuery.class,
                    new CountSubjectCacheKeysQueryActuator(consentDecisionService));
            qd.registerActuator(
                    DeleteSubjectCacheKeysQueryActuator.DeleteSubjectCacheKeysQuery.class,
                    new DeleteSubjectCacheKeysQueryActuator(consentDecisionService));

            var consentGraphService = builder.consentGraphService();
            if (consentGraphService != null) {
                qd.registerActuator(
                        GetConsentGraphQueryActuator.GetConsentGraphQuery.class,
                        new GetConsentGraphQueryActuator(consentGraphService));
            }

            LOG.info(
                    "Operio REST API: Redis-backed query actuators registered (evaluate, cache"
                            + " count, cache delete, graph)");
        }

        Operio operio = builder.build();
        LOG.info(
                "Operio REST API: initialized successfully (appName={})",
                builder.appName() != null ? builder.appName() : Operio.DEFAULT_APP_NAME);
        return operio;
    }

    // -----------------------------------------------------------------------
    // Sub-service producers
    // -----------------------------------------------------------------------

    /**
     * Produces the {@link OperioHealthService} singleton — verifies readiness of all configured
     * plugins. Replaces the former {@code Operio.verifyReady()} used by HealthResource.
     */
    @Produces
    @ApplicationScoped
    public OperioHealthService healthService() {
        return new OperioHealthService(healthPlugins != null ? healthPlugins : java.util.List.of());
    }

    /**
     * Produces a {@code null} {@link TokenValidationService} — API key hash-based authentication
     * has been removed in favor of Keycloak JWT. Retained for CDI injection compatibility.
     *
     * @param operio the built {@link Operio} instance (ensures CDI initialization ordering)
     * @return {@code null} — no token validation service needed
     */
    @Produces
    @ApplicationScoped
    @Unremovable
    @SuppressWarnings("rawtypes")
    public TokenValidationService tokenValidationService(Operio operio) {
        operio.appName();
        LOG.debug("Operio REST API: TokenValidationService not used (JWT auth active)");
        return null;
    }

    /**
     * Produces a concrete non-generic {@link PlatformCqrsHolder} that wraps all platform CQRS
     * services. This avoids Quarkus ArC's inability to resolve raw parameterized types via {@code
     * Instance<T>}.
     */
    @Produces
    @ApplicationScoped
    @SuppressWarnings("rawtypes")
    public PlatformCqrsHolder platformCqrsHolder(Operio operio) {
        // Force CDI proxy resolution so operioInit() completes before we
        // snapshot the infrastructure fields into the immutable holder.
        operio.appName();
        LOG.debug(
                "PlatformCqrsHolder — iamCmd={}, iamQuery={}, coreQuery={}, coreCmd={}",
                iamDispatcher != null,
                iamQueryService != null,
                coreQueryService != null,
                coreCommandService != null);
        return new PlatformCqrsHolder(
                iamDispatcher,
                iamQueryService,
                coreQueryService,
                coreCommandService,
                billingCommandService,
                planQueryService,
                null);
    }

    /**
     * Produces the consent admin {@link CommandDispatcher} for direct command-record dispatch from
     * REST resources. Returns {@code null} in embedded mode where the consent command service is
     * not configured as a dispatcher.
     */
    @Produces
    @ApplicationScoped
    @Named("consentDispatcher")
    public CommandDispatcher consentDispatcher() {
        if (consentAdminCommandService != null) {
            return consentAdminCommandService;
        }
        LOG.debug("Operio REST API: consent CommandDispatcher not available (embedded mode)");
        return null;
    }

    /**
     * Produces the consent admin {@link QueryDispatcher} for direct query-record dispatch from REST
     * resources. Returns {@code null} in embedded mode where the consent query service is not
     * configured as a dispatcher.
     */
    @Produces
    @ApplicationScoped
    @Named("consentQueryDispatcher")
    public QueryDispatcher consentQueryDispatcher() {
        if (consentAdminQueryDispatcher != null) {
            return consentAdminQueryDispatcher;
        }
        LOG.debug("Operio REST API: consent QueryDispatcher not available (embedded mode)");
        return null;
    }

    /**
     * Produces the core {@link CommandDispatcher} for direct command-record dispatch from REST
     * resources. Returns {@code null} in embedded mode where the core command service is not
     * configured as a dispatcher.
     */
    @Produces
    @ApplicationScoped
    @Named("coreDispatcher")
    public CommandDispatcher coreDispatcher() {
        if (coreCommandService instanceof CommandDispatcher cd) {
            return cd;
        }
        LOG.debug("Operio REST API: core CommandDispatcher not available (embedded mode)");
        return null;
    }

    /**
     * Produces the billing {@link CommandDispatcher} for direct command-record dispatch from REST
     * resources. Returns {@code null} in embedded mode where the billing command service is not
     * configured as a dispatcher.
     */
    @Produces
    @ApplicationScoped
    @Named("billingDispatcher")
    public CommandDispatcher billingDispatcher() {
        if (billingCommandService instanceof CommandDispatcher cd) {
            return cd;
        }
        LOG.debug("Operio REST API: billing CommandDispatcher not available (embedded mode)");
        return null;
    }

    /**
     * Produces the {@link IdpProvider} singleton — the platform default identity provider
     * (Keycloak).
     *
     * <p>In production mode with Keycloak configured, this returns a {@code KeycloakIdpProvider}
     * connected to the configured Keycloak instance. In embedded mode or when Keycloak is not
     * configured, returns {@code null}.
     */
    @Produces
    @ApplicationScoped
    @Unremovable
    @SuppressWarnings({"rawtypes", "unchecked"})
    public IdpProvider idpProvider() {
        if (!MODE_PRODUCTION.equalsIgnoreCase(config.mode())) {
            LOG.debug("Operio REST API: IdpProvider not available (embedded mode)");
            return IdpProvider.unavailable();
        }

        return config.keycloak()
                .filter(kc -> kc.url().isPresent() && !kc.url().get().isBlank())
                .map(
                        kc -> {
                            com.koostack.operio.plugin.platform.keycloak.KeycloakConfig kcConfig =
                                    new com.koostack.operio.plugin.platform.keycloak.KeycloakConfig(
                                            kc.url().get(),
                                            "master",
                                            "admin-cli",
                                            kc.adminUsername(),
                                            kc.adminPassword().orElse(""),
                                            kc.clientId(),
                                            kc.clientSecret().orElse(null));
                            com.koostack.operio.plugin.platform.keycloak.KeycloakIdpProvider
                                    provider =
                                            new com.koostack.operio.plugin.platform.keycloak
                                                    .KeycloakIdpProvider(kcConfig);
                            LOG.info(
                                    "Operio REST API: KeycloakIdpProvider wired (url={})",
                                    kc.url().get());

                            // Bootstrap: ensure the operio realm exists
                            String rootEmail = kc.rootEmail().orElse(null);
                            String rootPassword = kc.rootPassword().orElse(null);
                            if (rootEmail != null && rootPassword != null) {
                                try {
                                    provider.ensureOperioRealm(rootEmail, rootPassword);
                                } catch (Exception e) {
                                    LOG.error(
                                            "Failed to ensure Operio realm — signup will not"
                                                    + " work until the realm is created",
                                            e);
                                }
                            } else {
                                LOG.info(
                                        "Operio REST API: Keycloak root-email/root-password not"
                                                + " configured — skipping realm bootstrap");
                            }

                            return (IdpProvider) provider;
                        })
                .orElseGet(
                        () -> {
                            LOG.info(
                                    "Operio REST API: No Keycloak configuration — IdpProvider not"
                                            + " available");
                            return IdpProvider.unavailable();
                        });
    }

    /**
     * Produces the {@link AuthzProvider} singleton — tenant-scoped authorization (role management)
     * backed by Keycloak.
     *
     * <p>In production mode with Keycloak configured, returns a {@code KeycloakAuthzProvider}. In
     * embedded mode or when Keycloak is not configured, returns {@code null}.
     */
    @Produces
    @ApplicationScoped
    @Unremovable
    @SuppressWarnings("rawtypes")
    public AuthzProvider authzProvider() {
        if (!MODE_PRODUCTION.equalsIgnoreCase(config.mode())) {
            LOG.debug("Operio REST API: AuthzProvider not available (embedded mode)");
            return null;
        }

        return config.keycloak()
                .filter(kc -> kc.url().isPresent() && !kc.url().get().isBlank())
                .map(
                        kc -> {
                            com.koostack.operio.tenant.authz.keycloak.KeycloakAuthzConfig
                                    authzConfig =
                                            new com.koostack.operio.tenant.authz.keycloak
                                                    .KeycloakAuthzConfig(
                                                    kc.url().get(),
                                                    "master",
                                                    "admin-cli",
                                                    kc.adminUsername(),
                                                    kc.adminPassword().orElse(""));
                            com.koostack.operio.tenant.authz.keycloak.KeycloakAuthzProvider
                                    provider =
                                            new com.koostack.operio.tenant.authz.keycloak
                                                    .KeycloakAuthzProvider(authzConfig);
                            LOG.info(
                                    "Operio REST API: KeycloakAuthzProvider wired (url={})",
                                    kc.url().get());
                            return (AuthzProvider) provider;
                        })
                .orElseGet(
                        () -> {
                            LOG.info(
                                    "Operio REST API: No Keycloak configuration — AuthzProvider not"
                                            + " available");
                            return null;
                        });
    }

    /**
     * Produces the {@link IdpTenantResolver} singleton — resolves which {@link IdpProvider} to use
     * for a given tenant.
     *
     * <p>Falls back to the platform default (Keycloak) when no per-tenant IdP override is
     * configured.
     */
    @Produces
    @ApplicationScoped
    @SuppressWarnings("rawtypes")
    public IdpTenantResolver idpTenantResolver(Instance<IdpProvider> defaultIdpProviderInstance) {
        if (!defaultIdpProviderInstance.isResolvable()) {
            LOG.debug("Operio REST API: IdpTenantResolver — no IdpProvider bean found");
            return IdpTenantResolver.unavailable();
        }
        IdpProvider<?> defaultIdpProvider = defaultIdpProviderInstance.get();
        if (defaultIdpProvider == null || !defaultIdpProvider.isAvailable()) {
            LOG.debug("Operio REST API: IdpTenantResolver — IdP not available");
            return IdpTenantResolver.unavailable();
        }

        // TODO: Wire per-tenant IdP config lookup from the CQRS query layer
        // For now, all tenants use the platform default (Keycloak).
        return new IdpTenantResolver() {
            @Override
            public IdpProvider<?> resolve(String tenantId) {
                // Future: check idp_config table for tenant-specific override
                return defaultIdpProvider;
            }

            @Override
            public IdpProvider<?> defaultProvider() {
                return defaultIdpProvider;
            }
        };
    }

    // -----------------------------------------------------------------------
    // Per-tenant stream consumer (production only)
    // -----------------------------------------------------------------------

    private ShardedStreamConsumer tenantStreamConsumer;
    private AbstractStreamConsumer platformStreamConsumer;
    private SubscriptionManager adminSubscriptionManager;
    private com.koostack.operio.tenant.consent.projection.decision.ExpiredConsentReaper
            expiredConsentReaper;

    /** Platform DataSource for RLS-aware DSLContext creation. */
    private DataSource platformDataSource;

    /** Platform DSLContext set during wirePlatformCqrsServices() — used for tenant hydration. */
    private DSLContext platformDsl;

    /**
     * Produces the {@link ShardedStreamConsumer} that runs both the consent decision projection and
     * admin projection — materializing domain events into the Redis decision cache and SQL admin
     * tables respectively.
     *
     * <p>Only activated in production mode (when {@code adminRedisPool} and {@code
     * tenantAwareJedisProvider} are available). In embedded mode this returns {@code null} (no
     * stream consumer needed).
     *
     * <p>Uses fixed shard streams ({@code operio:stream:shard:{0..N-1}}) instead of per-tenant
     * streams. The shard count is configurable via {@code operio.stream.shard-count} (default 8).
     *
     * @param operio the built {@link Operio} instance (triggers bundle initialization first)
     * @return the sharded stream consumer, or {@code null} in embedded mode
     */
    @Produces
    @ApplicationScoped
    @io.quarkus.runtime.Startup
    public ShardedStreamConsumer tenantStreamConsumer(Operio operio) {
        // Force CDI proxy resolution so operioInit() completes before we
        // inspect the infrastructure fields it populates (adminRedisPool, etc.).
        operio.appName();

        if (adminRedisPool == null || tenantAwareJedisProvider == null) {
            LOG.info("Operio REST API: tenant stream consumer not wired (embedded mode)");
            return null;
        }

        // Graph projection handler — replaces both the old decision projection
        // and admin Redis projection with a unified graph-based handler
        ConsentGraphRedisRepository graphRepo =
                new ConsentGraphRedisRepository(tenantAwareJedisProvider);

        RedisInvalidationPublisher invalidationPublisher =
                new RedisInvalidationPublisher(
                        adminRedisPool, new ObjectMapper().registerModule(new JavaTimeModule()));

        ConsentGraphProjectionHandler graphHandler =
                new ConsentGraphProjectionHandler(graphRepo, invalidationPublisher);

        // Expired consent reaper — periodically GCs consent edges past expiresAt
        if (coreQueryService != null) {
            @SuppressWarnings("unchecked")
            CoreQueryService<?> typedCqs = coreQueryService;
            java.util.function.Supplier<java.util.List<String>> tenantDiscovery =
                    () -> {
                        try {
                            return typedCqs.listTenants().stream().map(t -> t.tenantId()).toList();
                        } catch (Exception e) {
                            LOG.warn("Reaper tenant discovery failed", e);
                            return java.util.List.of();
                        }
                    };
            this.expiredConsentReaper =
                    new com.koostack.operio.tenant.consent.projection.decision.ExpiredConsentReaper(
                            graphRepo, invalidationPublisher, tenantDiscovery);
            this.expiredConsentReaper.start();
            LOG.info("Operio REST API: ExpiredConsentReaper started");
        } else {
            LOG.warn(
                    "Operio REST API: ExpiredConsentReaper NOT started — CoreQueryService"
                            + " unavailable");
        }

        // Admin projection handler — uses an RLS-aware DSLContext from the
        // platform datasource. TenantAwareExecuteListener sets app.tenant_id
        // for RLS filtering on each SQL statement.
        DSLContext rlsAwareDsl = null;
        if (this.platformDataSource != null) {
            org.jooq.impl.DefaultConfiguration rlsConfig = new org.jooq.impl.DefaultConfiguration();
            rlsConfig.set(this.platformDataSource);
            rlsConfig.set(SQLDialect.POSTGRES);
            rlsConfig.set(
                    new org.jooq.conf.Settings()
                            .withRenderNameCase(org.jooq.conf.RenderNameCase.LOWER)
                            .withRenderSchema(false));
            rlsConfig.set(
                    new org.jooq.impl.DefaultExecuteListenerProvider(
                            new com.koostack.operio.plugin.jooq.common
                                    .TenantAwareExecuteListener()));
            rlsAwareDsl = DSL.using(rlsConfig);
        }

        // Build sharded consumer with handlers.
        // Uses fixed shard streams instead of per-tenant streams.
        // Block timeout of 500ms balances responsiveness with CPU usage —
        // virtual threads unmount during the blocking XREADGROUP so the cost
        // is negligible.
        int shardCount =
                config.stream()
                        .map(OperioConfig.StreamConfig::shardCount)
                        .orElse(RedisEventPublisher.DEFAULT_SHARD_COUNT);
        ShardedStreamConsumer consumer =
                new ShardedStreamConsumer(
                        JedisProvider.shared(adminRedisPool), shardCount, 200L, 100);
        consumer.registerHandler("graph-projection", graphHandler);

        if (rlsAwareDsl != null) {
            ConsentAdminProjectionHandler adminHandler =
                    new ConsentAdminProjectionHandler(rlsAwareDsl);
            consumer.registerHandler("admin-projection", adminHandler);
            LOG.info("Operio REST API: admin projection handler wired (group=admin-projection)");
        } else {
            LOG.warn(
                    "Operio REST API: admin projection handler NOT wired — platform datasource"
                            + " not available");
        }

        // SSE publisher: reads from Redis Pub/Sub and publishes to SSE clients.
        // Channel prefix must match RedisInvalidationPublisher so that
        // mutations flow through Redis → SSE → client.
        ObjectMapper adminRedisMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try {
            RedisInvalidationSubscriber adminSubscriber =
                    new RedisInvalidationSubscriber(
                            adminRedisPool, adminRedisMapper, "operio:consent-cache-invalidation:");
            this.adminSubscriptionManager = new SubscriptionManager(adminSubscriber);
            LOG.info("Operio REST API: SSE publisher wired (channel=consent-cache-invalidation)");
        } catch (Exception e) {
            LOG.warn("Operio REST API: SSE publisher NOT wired — Redis Pub/Sub init failed", e);
        }

        // TenantIamProjectionHandler removed — single Keycloak realm with
        // Organizations eliminates per-realm event materialization.

        this.tenantStreamConsumer = consumer;

        // Start the sharded consumer — no per-tenant registration needed.
        // Shard streams are fixed and created at startup.
        consumer.start();

        LOG.info(
                "Operio REST API: sharded stream consumer started"
                        + " ({} shards, groups=graph-projection,admin-projection)",
                shardCount);
        return consumer;
    }

    /**
     * Produces the admin {@link SubscriptionManager} that bridges Redis Pub/Sub invalidation
     * signals to SSE endpoints for consent admin aggregate changes.
     */
    @Produces
    @ApplicationScoped
    @Named("adminSubscriptionManager")
    public SubscriptionManager adminSubscriptionManager() {
        return adminSubscriptionManager;
    }

    /** CDI shutdown hook — stops the stream consumer gracefully. */
    public void onShutdown(
            @jakarta.enterprise.event.Observes
                    @jakarta.enterprise.context.BeforeDestroyed(ApplicationScoped.class)
                    Object event) {
        if (tenantStreamConsumer != null) {
            LOG.info("Operio REST API: stopping sharded stream consumer");
            tenantStreamConsumer.close();
        }
        if (adminSubscriptionManager != null) {
            LOG.info("Operio REST API: stopping admin subscription manager");
            try {
                adminSubscriptionManager.close();
            } catch (Exception e) {
                LOG.warn("Error closing admin subscription manager", e);
            }
        }
        if (platformStreamConsumer != null) {
            LOG.info("Operio REST API: stopping platform stream consumer");
            platformStreamConsumer.close();
        }
        if (expiredConsentReaper != null) {
            LOG.info("Operio REST API: stopping expired consent reaper");
            expiredConsentReaper.stop();
        }
    }

    // -----------------------------------------------------------------------
    // Platform CQRS wiring (production only)
    // -----------------------------------------------------------------------

    /**
     * Creates and wires all platform CQRS services: event service, command services, query
     * services, projection handlers, and the platform stream consumer.
     *
     * <p>Called once during {@link #tenantService()} initialization in production mode. The command
     * and query services are stored as instance fields and exposed via {@link PlatformCqrsHolder}.
     */
    private void wirePlatformCqrsServices(Operio.Builder builder) {
        OperioConfig.DbConfig platformDb = config.platformDb().orElse(null);
        if (platformDb == null) {
            LOG.warn(
                    "Operio REST API: platform CQRS services NOT wired — operio.platform-db not"
                            + " configured");
            return;
        }

        String platformDbUrl = appendSslParameters(platformDb.url(), platformDb);
        String appName = config.appName().orElse(Operio.DEFAULT_APP_NAME);

        // -- Redis config (optional) ------------------------------------------
        RedisPluginConfig redisPluginConfig =
                config.redis()
                        .<RedisPluginConfig>map(
                                redis -> {
                                    String pw =
                                            redis.password().filter(p -> !p.isBlank()).orElse(null);
                                    RedisClientFactory.TlsConfig tls =
                                            redis.tlsEnabled()
                                                    ? new RedisClientFactory.TlsConfig(
                                                            true,
                                                            redis.tlsCaCertPath().orElse(null),
                                                            redis.tlsVerifyPeer())
                                                    : null;
                                    return new RedisPluginConfig() {
                                        @Override
                                        public String host() {
                                            return redis.host();
                                        }

                                        @Override
                                        public int port() {
                                            return redis.port();
                                        }

                                        @Override
                                        public String password() {
                                            return pw;
                                        }

                                        @Override
                                        public RedisClientFactory.TlsConfig tlsConfig() {
                                            return tls;
                                        }
                                    };
                                })
                        .orElse(null);

        // -- Platform event service -------------------------------------------
        // Include operio_event in the search_path for the event service's DSLContext.
        // The JooqDataSourceFactory creates its own HikariCP pool without a
        // connectionInitSql, so we use the JDBC currentSchema parameter instead.
        String eventDbUrl = platformDbUrl;
        if (eventDbUrl != null && eventDbUrl.startsWith("jdbc:postgresql")) {
            String sep = eventDbUrl.contains("?") ? "&" : "?";
            eventDbUrl = eventDbUrl + sep + "currentSchema=operio_event,public";
        }
        int eventShardCount =
                config.stream()
                        .map(OperioConfig.StreamConfig::shardCount)
                        .orElse(RedisEventPublisher.DEFAULT_SHARD_COUNT);
        PlatformEventJooqConfig eventConfig =
                new PlatformEventJooqConfig(
                        eventDbUrl,
                        platformDb.dialect(),
                        platformDb.username().orElse(null),
                        platformDb.password().orElse(null),
                        redisPluginConfig,
                        null,
                        eventShardCount);

        // Run CQRS schema migrations (event store + domain schemas).
        // The event-sourced CQRS modules need their own migrations.
        DataSource migrationDs =
                JooqDataSourceFactory.createDataSource(eventConfig, "operio-cqrs-migrations");

        // Unified event store lives in operio_event schema. All consent tables
        // live in operio_consent. Metrics in operio_metrics.
        // -- Unified schema migrations (all schemas created at startup) -----
        try (var conn = migrationDs.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS operio_event");
            stmt.execute("CREATE SCHEMA IF NOT EXISTS operio_consent");
            stmt.execute("CREATE SCHEMA IF NOT EXISTS operio_metrics");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create unified schemas", e);
        }

        runFlywayInSchema(
                migrationDs,
                "classpath:db/migration/platform-event-postgresql",
                "flyway_schema_history_event",
                "operio_event");
        runFlywayInSchema(
                migrationDs,
                "classpath:db/migration/consent",
                "flyway_schema_history_consent",
                "operio_consent");
        runFlywayInSchema(
                migrationDs,
                "classpath:db/migration/platform-metrics",
                "flyway_schema_history_metrics",
                "operio_metrics");

        // Domain projection schemas (self-contained — each V1 creates its own schema).
        runFlywayInSchema(
                migrationDs,
                "classpath:db/migration/platform-core",
                "flyway_schema_history",
                "platform_core");
        runFlywayInSchema(
                migrationDs,
                "classpath:db/migration/platform-billing",
                "flyway_schema_history",
                "platform_billing");
        runFlywayInSchema(
                migrationDs,
                "classpath:db/migration/platform-iam",
                "flyway_schema_history",
                "platform_iam");

        // RLS policies — must run AFTER all schema migrations complete.
        // Uses 'public' schema for the flyway history table since RLS
        // policies span multiple schemas.
        runFlyway(migrationDs, "classpath:db/migration/rls-policies", "flyway_schema_history_rls");

        JooqPlatformEventService platformEventService = new JooqPlatformEventService();
        platformEventService.configure(eventConfig);
        this.platformEventService = platformEventService;

        // -- Command services -------------------------------------------------
        JooqCoreDispatcher coreCmdSvc = new JooqCoreDispatcher();
        coreCmdSvc.configure(new JooqCoreCommandConfig(platformEventService));

        JooqBillingDispatcher billingCmdSvc = new JooqBillingDispatcher();
        // Billing dispatcher configured after planQueryService is available (below)

        com.koostack.operio.platform.iam.jooq.KeycloakIamDispatcher iamCmdSvc =
                new com.koostack.operio.platform.iam.jooq.KeycloakIamDispatcher();
        // IAM command service configured later after keycloakAdminClient is available

        this.coreCommandService = coreCmdSvc;
        this.billingCommandService = billingCmdSvc;
        this.iamDispatcher = iamCmdSvc;

        builder.withCoreCommandService(coreCmdSvc);
        builder.withBillingCommandService(billingCmdSvc);

        // -- Platform DSLContext for queries + projections ---------------------
        // Set search_path on every connection so unqualified table names
        // resolve to the correct CQRS projection schemas.
        com.zaxxer.hikari.HikariConfig hikariCfg = new com.zaxxer.hikari.HikariConfig();
        String cqrsJdbcUrl = eventConfig.connectionString();
        if (cqrsJdbcUrl != null && cqrsJdbcUrl.startsWith("jdbc:postgresql")) {
            String sep = cqrsJdbcUrl.contains("?") ? "&" : "?";
            cqrsJdbcUrl = cqrsJdbcUrl + sep + "stringtype=unspecified";
        }
        hikariCfg.setJdbcUrl(cqrsJdbcUrl);
        hikariCfg.setPoolName("operio-platform-cqrs");
        if (eventConfig.username() != null) hikariCfg.setUsername(eventConfig.username());
        if (eventConfig.password() != null) hikariCfg.setPassword(eventConfig.password());
        hikariCfg.setConnectionInitSql(
                "SET search_path TO platform_core, platform_billing, platform_iam, operio_event,"
                        + " operio_consent, operio_metrics, public");
        DataSource platformDataSource = new com.zaxxer.hikari.HikariDataSource(hikariCfg);
        this.platformDataSource = platformDataSource;
        DSLContext platformDsl =
                DSL.using(
                        platformDataSource,
                        SQLDialect.POSTGRES,
                        new org.jooq.conf.Settings()
                                .withRenderNameCase(org.jooq.conf.RenderNameCase.LOWER)
                                .withRenderSchema(false));
        this.platformDsl = platformDsl;

        // -- Query services ---------------------------------------------------
        // Build a decryptor for encrypted DB fields (e.g. redis_acl_password).
        String encryptionKey = platformDb.encryptionKey().filter(k -> !k.isBlank()).orElse(null);
        java.util.function.UnaryOperator<String> fieldDecryptor =
                encryptionKey != null
                        ? raw ->
                                com.koostack.operio.plugin.platform.jooq.crypto.FieldEncryptor
                                        .decryptIfKeyPresent(raw, encryptionKey)
                        : java.util.function.UnaryOperator.identity();

        // IAM command service configured later after keycloakAdminClient is available

        CoreQueryDispatcher coreQuerySvc = new CoreQueryDispatcher();
        coreQuerySvc.configure(new JooqCoreQueryConfig(platformDsl, fieldDecryptor));

        BillingQueryDispatcher billingQuerySvc = new BillingQueryDispatcher();
        billingQuerySvc.configure(new JooqBillingQueryConfig(platformDsl));

        com.koostack.operio.platform.plan.jooq.JooqPlanQueryService planQuerySvc =
                new com.koostack.operio.platform.plan.jooq.JooqPlanQueryService();
        planQuerySvc.configure(
                new com.koostack.operio.platform.plan.jooq.JooqPlanQueryConfig(platformDsl));

        this.coreQueryService = coreQuerySvc;
        this.planQueryService = planQuerySvc;

        // Configure billing dispatcher now that planQueryService is available
        billingCmdSvc.configure(new JooqBillingCommandConfig(platformEventService, planQuerySvc));

        com.koostack.operio.platform.iam.jooq.KeycloakIamQueryDispatcher iamQuerySvc =
                new com.koostack.operio.platform.iam.jooq.KeycloakIamQueryDispatcher();

        // Build KeycloakConfig for IAM query service
        com.koostack.operio.plugin.platform.keycloak.KeycloakConfig kcIamConfig = null;
        if (config.keycloak().isPresent()) {
            var kc = config.keycloak().get();
            if (kc.url().isPresent() && kc.adminPassword().isPresent()) {
                kcIamConfig =
                        new com.koostack.operio.plugin.platform.keycloak.KeycloakConfig(
                                kc.url().get(),
                                "master",
                                "admin-cli",
                                kc.adminUsername(),
                                kc.adminPassword().get(),
                                kc.clientId(),
                                kc.clientSecret().orElse(null));
            }
        }
        // Build KeycloakOrganizationService for org management in IAM actuators
        com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService orgService = null;

        if (kcIamConfig != null) {
            // Wire IAM query with org service (created after Keycloak admin client below)
            iamQuerySvc.configure(
                    new com.koostack.operio.platform.iam.jooq.JooqIamQueryConfig(
                            kcIamConfig, platformDataSource));
        } else {
            LOG.warn("Operio REST API: IamQueryService NOT configured — Keycloak not available");
        }
        this.iamQueryService = iamQuerySvc;

        // Wire query service into command service for email/username → UUID resolution
        if (iamCmdSvc
                instanceof com.koostack.operio.platform.iam.jooq.KeycloakIamDispatcher dispatcher) {
            dispatcher.setQueryService(iamQuerySvc);
            LOG.info("Operio REST API: KeycloakIamDispatcher wired with IamQueryService");
        }

        // -- Tenant IAM services eliminated (Phase 9) ---
        // TenantIamResource and TenantIamHolder deleted. All IAM operations
        // go through PlatformIamResource → unified KeycloakIamDispatcher.
        LOG.info("Operio REST API: tenant IAM services removed — unified IAM active");

        builder.withCoreQueryService(coreQuerySvc);
        builder.withBillingQueryService(billingQuerySvc);

        LOG.info("Operio REST API: platform command + query services wired");

        // -- Keycloak admin client (shared by IAM command + projection) --------
        org.keycloak.admin.client.Keycloak iamKeycloakAdmin = null;
        if (config.keycloak().isPresent()) {
            var kcCfg = config.keycloak().get();
            if (kcCfg.url().isPresent() && kcCfg.adminPassword().isPresent()) {
                iamKeycloakAdmin =
                        org.keycloak.admin.client.KeycloakBuilder.builder()
                                .serverUrl(kcCfg.url().get())
                                .realm("master")
                                .clientId("admin-cli")
                                .username(kcCfg.adminUsername())
                                .password(kcCfg.adminPassword().get())
                                .build();
            }
        }
        this.keycloakAdminClient = iamKeycloakAdmin;

        // -- KeycloakOrganizationService (org = tenant in new architecture) ----
        if (iamKeycloakAdmin != null && kcIamConfig != null) {
            String realmName =
                    kcIamConfig.operioRealmName() != null
                            ? kcIamConfig.operioRealmName()
                            : "operio";
            orgService =
                    new com.koostack.operio.plugin.platform.keycloak.KeycloakOrganizationService(
                            iamKeycloakAdmin, realmName);
            LOG.info("Operio REST API: KeycloakOrganizationService wired (realm={})", realmName);
        }

        // Configure IAM command service now that keycloakAdminClient + platformDataSource +
        // orgService are available
        org.keycloak.admin.client.Keycloak iamCmdKeycloak =
                iamKeycloakAdmin != null
                        ? iamKeycloakAdmin
                        : org.keycloak.admin.client.KeycloakBuilder.builder()
                                .serverUrl("http://localhost:8180")
                                .realm("master")
                                .clientId("admin-cli")
                                .username("admin")
                                .password("admin")
                                .build();
        iamCmdSvc.configure(
                new com.koostack.operio.platform.iam.jooq.JooqIamCommandConfig(
                        platformEventService,
                        platformDataSource,
                        iamCmdKeycloak,
                        kcIamConfig,
                        orgService,
                        adminRedisPool));
        iamCmdSvc.registerAuthActuators();
        LOG.info(
                "Operio REST API: IAM command service configured (orgService={}, redis={})",
                orgService != null,
                adminRedisPool != null);

        // Reconfigure IAM query service with org service now that it's available
        if (kcIamConfig != null && orgService != null) {
            iamQuerySvc.configure(
                    new com.koostack.operio.platform.iam.jooq.JooqIamQueryConfig(
                            kcIamConfig, platformDataSource, orgService));
            LOG.info("Operio REST API: IAM query service reconfigured with orgService");
        }

        // -- Projection handlers + stream consumer (requires Redis) -----------
        if (redisPluginConfig == null || adminRedisPool == null) {
            LOG.warn("Operio REST API: platform stream consumer NOT wired — Redis not configured");
            return;
        }

        CoreProjectionHandler coreProjection = new CoreProjectionHandler(platformDsl);
        BillingProjectionHandler billingProjection = new BillingProjectionHandler(platformDsl);

        com.koostack.operio.platform.iam.jooq.IamProjectionHandler iamProjection =
                new com.koostack.operio.platform.iam.jooq.IamProjectionHandler(iamCmdKeycloak);

        // TenantManagerService and provisioning pipeline eliminated (Phase 8).
        // Tenant creation is now synchronous via RegisterUserOrchestrator.

        // -- Platform stream consumer -----------------------------------------
        AbstractStreamConsumer consumer =
                new AbstractStreamConsumer(JedisProvider.shared(adminRedisPool), 200L, 100) {
                    @Override
                    protected String streamKeyForTenant(String tenantId) {
                        return "operio:platform-events";
                    }
                };

        consumer.registerHandler("core-projection", (StreamHandler) coreProjection::onEvent);
        consumer.registerHandler("billing-projection", (StreamHandler) billingProjection::onEvent);
        consumer.registerHandler("iam-projection", (StreamHandler) iamProjection::onEvent);
        consumer.addTenant("platform");

        this.platformStreamConsumer = consumer;
        LOG.info(
                "Operio REST API: platform stream consumer started"
                        + " (groups=core-projection,billing-projection,iam-projection)");
    }

    // -----------------------------------------------------------------------
    // Internal wiring helpers
    // -----------------------------------------------------------------------

    /**
     * Replays tenant lifecycle events from the platform event log to determine the current set of
     * active tenants. This is the write-side source of truth — no projection dependency.
     *
     * <p>Active = has {@code TENANT_CREATED} or {@code TENANT_ACTIVATED} as its most recent
     * lifecycle event (not {@code TENANT_SUSPENDED} or {@code TENANT_DELETED}).
     */
    private static java.util.Set<String> hydrateTenantSetFromEventLog(DSLContext dsl) {
        // Query all tenant lifecycle events from the platform event store,
        // ordered by version ascending so we can replay them sequentially.
        var lifecycleTypes =
                java.util.Set.of(
                        com.koostack.operio.core.platform.core.CoreEventTypes.TENANT_CREATED,
                        com.koostack.operio.core.platform.core.CoreEventTypes.TENANT_ACTIVATED,
                        com.koostack.operio.core.platform.core.CoreEventTypes.TENANT_SUSPENDED,
                        com.koostack.operio.core.platform.core.CoreEventTypes.TENANT_DELETED);

        var records =
                dsl.select(
                                org.jooq.impl.DSL.field("event_type", String.class),
                                org.jooq.impl.DSL.field("payload", String.class))
                        .from(org.jooq.impl.DSL.table("event"))
                        .where(org.jooq.impl.DSL.field("event_type").in(lifecycleTypes))
                        .orderBy(org.jooq.impl.DSL.field("event_id").asc())
                        .fetch();

        var activeTenants = new java.util.HashSet<String>();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        for (var record : records) {
            String eventType = record.get(0, String.class);
            String payload = record.get(1, String.class);
            String tenantId = null;
            try {
                var node = mapper.readTree(payload);
                var tid = node.get("tenantId");
                if (tid != null && !tid.isNull()) tenantId = tid.asText();
            } catch (Exception e) {
                LOG.warn("Failed to parse tenant event payload: {}", payload, e);
                continue;
            }
            if (tenantId == null) continue;

            if (com.koostack.operio.core.platform.core.CoreEventTypes.TENANT_CREATED.equals(
                            eventType)
                    || com.koostack.operio.core.platform.core.CoreEventTypes.TENANT_ACTIVATED
                            .equals(eventType)) {
                activeTenants.add(tenantId);
            } else {
                activeTenants.remove(tenantId);
            }
        }

        return activeTenants;
    }

    /**
     * Creates the appropriate consent bundle based on the deployment mode.
     *
     * @param mode the deployment mode ({@code "production"} or {@code "embedded"})
     * @return the consent bundle
     * @throws IllegalStateException if the mode is unknown or required configuration is missing
     */
    private PluginBundle createConsentBundle(String mode) {
        if (MODE_PRODUCTION.equalsIgnoreCase(mode)) {
            return createCoreConsentBundle();
        }
        if (MODE_EMBEDDED.equalsIgnoreCase(mode)) {
            return createEmbeddedConsentBundle();
        }
        throw new IllegalStateException(
                "Unknown operio.mode: '"
                        + mode
                        + "'. "
                        + "Valid values are 'production' and 'embedded'.");
    }

    /**
     * Creates a {@link CoreConsentBundle} (Postgres + Redis) from the {@code operio.db} and {@code
     * operio.redis} configuration.
     *
     * @return the production consent bundle
     * @throws IllegalStateException if {@code operio.db} or {@code operio.redis} is not configured
     */
    /**
     * Holds the {@link TenantAwareJedisProvider} created during consent bundle construction so it
     * can be shared with the {@link com.koostack.operio.rest.ratelimit.RateLimitService} and shut
     * down cleanly.
     */
    private TenantAwareJedisProvider tenantAwareJedisProvider;

    /**
     * Holds the admin Redis pool created during consent bundle construction so it can be shared
     * with the invalidation publisher and stream consumer.
     */
    private JedisPooled adminRedisPool;

    private CoreConsentBundle createCoreConsentBundle() {
        OperioConfig.DbConfig db =
                config.db()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "operio.mode=production requires operio.db.url to"
                                                        + " be configured"));
        OperioConfig.RedisConfig redis =
                config.redis()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "operio.mode=production requires operio.redis to be"
                                                        + " configured"));

        // -- Validate credentials are present and non-blank -------------------
        requireNonBlank(db.url(), "operio.db.url");
        requireNonBlank(db.username().orElse(null), "operio.db.username (env: OPERIO_DB_USERNAME)");
        requireNonBlank(db.password().orElse(null), "operio.db.password (env: OPERIO_DB_PASSWORD)");

        // Append SSL parameters to JDBC URL if needed
        String dbUrl = appendSslParameters(db.url(), db);

        // Consent tables live in operio_consent; event table in operio_event.
        // Append currentSchema so jOOQ's renderSchema(false) resolves unqualified
        // table names to the correct schemas.
        if (dbUrl != null && dbUrl.startsWith("jdbc:postgresql")) {
            String sep = dbUrl.contains("?") ? "&" : "?";
            dbUrl =
                    dbUrl
                            + sep
                            + "currentSchema=operio_consent,operio_event,platform_core,"
                            + "platform_iam,platform_billing,public";
        }

        JooqConsentAdminServiceConfig jooqConfig =
                new JooqConsentAdminServiceConfig(
                        dbUrl,
                        db.dialect(),
                        db.username().orElse(null),
                        db.password().orElse(null));

        String appName = config.appName().orElse(null);

        LOG.info(
                "Operio REST API: configuring CoreConsentBundle (db={}, redis={}:{}, appName={},"
                        + " tenantAclIsolation=enabled, db-sslmode={})",
                db.url(),
                redis.host(),
                redis.port(),
                appName != null ? appName : "<default>",
                db.sslMode());

        // -- Build the admin Redis pool (default user) -----------------------
        // This pool is used for platform-level operations (provisioning,
        // health checks, migrations) where no tenant context is active.

        // Create TLS config if enabled
        RedisClientFactory.TlsConfig redisTlsConfig = null;
        if (redis.tlsEnabled()) {
            redisTlsConfig =
                    new RedisClientFactory.TlsConfig(
                            true, redis.tlsCaCertPath().orElse(null), redis.tlsVerifyPeer());
            LOG.info(
                    "Operio REST API: Redis TLS ENABLED " + "(host={}, port={}, verify-peer={})",
                    redis.host(),
                    redis.port(),
                    redis.tlsVerifyPeer());
        } else {
            LOG.warn(
                    "Operio REST API: Redis TLS DISABLED — connections are UNENCRYPTED. This is"
                        + " insecure for production deployments. Set operio.redis.tls-enabled=true"
                        + " and configure TLS ports. Required for HIPAA §164.312(a)(2)(ii) and GDPR"
                        + " Article 32 compliance.");
        }

        String redisPw = redis.password().filter(pw -> !pw.isBlank()).orElse(null);
        JedisPooled adminPool =
                RedisClientFactory.createClient(
                        redis.host(), redis.port(), redisPw, redisTlsConfig);
        this.adminRedisPool = adminPool;

        // -- Build the TenantAwareJedisProvider ------------------------------
        // This provider returns per-tenant JedisPooled connections that
        // authenticate as the tenant's dedicated Redis ACL user (e.g.
        // t_acme), which is restricted to keys matching
        // {appName}:{tenantId}:* by the Redis ACL rule set during
        // TenantManager.provisionTenant(). Even if application-level
        // key-prefix logic has a bug, Redis itself will reject operations
        // on keys outside the tenant's permitted pattern — true defense
        // in depth.
        //
        // The credential lookup queries the platform tenant table for the
        // redis_acl_user and redis_acl_password columns. This lookup is
        // called once per tenant (result is cached in the provider).
        this.tenantAwareJedisProvider =
                createTenantAwareJedisProvider(redis, adminPool, redisTlsConfig);

        if (appName != null && !appName.isBlank()) {
            return new CoreConsentBundle(appName, jooqConfig, tenantAwareJedisProvider);
        }
        return new CoreConsentBundle(null, jooqConfig, tenantAwareJedisProvider);
    }

    /**
     * Creates the {@link TenantAwareJedisProvider} that powers per-tenant Redis ACL isolation.
     *
     * <p>The provider caches per-tenant {@link JedisPooled} connections, each authenticated as the
     * tenant's dedicated Redis ACL user. The credential lookup delegates to {@link
     * CoreQueryService#getTenantCredentials(String)} which reads from the {@code
     * platform_core.tenant} table.
     *
     * @param redis the Redis connection config (host, port)
     * @param adminPool the admin-level JedisPooled (default user)
     * @param tlsConfig the TLS configuration (null if TLS is disabled)
     * @return the tenant-aware Jedis provider
     */
    private TenantAwareJedisProvider createTenantAwareJedisProvider(
            OperioConfig.RedisConfig redis,
            JedisPooled adminPool,
            RedisClientFactory.TlsConfig tlsConfig) {
        TenantAwareJedisProvider.TenantCredentialLookup lookup =
                tenantId -> {
                    @SuppressWarnings("rawtypes")
                    CoreQueryService cqs = coreQueryService;
                    if (cqs == null) {
                        LOG.error(
                                "Tenant '{}' credential lookup failed — CoreQueryService "
                                        + "is not yet initialized (startup ordering issue)",
                                tenantId);
                        return null;
                    }
                    var creds = cqs.getTenantCredentials(tenantId);
                    if (creds == null) {
                        LOG.warn(
                                "Tenant '{}' not found or has no Redis ACL credentials. "
                                        + "Run TenantManager.reprovisionRedisAcl() to generate "
                                        + "credentials for this tenant.",
                                tenantId);
                        return null;
                    }
                    return new TenantAwareJedisProvider.TenantRedisCredentials(
                            creds.redisAclUser(), creds.redisAclPassword());
                };

        LOG.info(
                "Operio REST API: TenantAwareJedisProvider created "
                        + "(redis={}:{}, per-tenant ACL isolation enabled)",
                redis.host(),
                redis.port());

        return new TenantAwareJedisProvider(
                redis.host(), redis.port(), adminPool, lookup, tlsConfig);
    }

    /**
     * Creates an {@code EmbeddedConsentBundle} (H2 + Caffeine) for local development and testing.
     *
     * <p>Uses default H2 in-memory settings. The application name (if configured) is set on the
     * {@link Operio.Builder} after the bundle is applied — see {@link #operio()} — rather than
     * passed to the bundle constructor, avoiding a compile-time dependency on {@code
     * AnnotationDiscoveryConfig}.
     *
     * <p><strong>Reflection-based loading:</strong> The embedded bundle is loaded via {@link
     * Class#forName} so that the {@code operio-bundle-embedded} module is not required on the
     * production runtime classpath. The production Docker image ships without the embedded bundle
     * (and without the H2 JDBC driver). If someone starts the API with {@code operio.mode=embedded}
     * but the module is not on the classpath, a clear error message is thrown explaining how to fix
     * it.
     *
     * @return the embedded consent bundle
     * @throws IllegalStateException if the embedded bundle classes are not on the classpath
     */
    private PluginBundle createEmbeddedConsentBundle() {
        LOG.info(
                "Operio REST API: configuring EmbeddedConsentBundle "
                        + "(H2 + Caffeine, appName={})",
                config.appName().filter(n -> !n.isBlank()).orElse("<default>"));

        try {
            Class<?> cls =
                    Class.forName("com.koostack.operio.bundle.embedded.EmbeddedConsentBundle");
            return (PluginBundle) cls.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "operio.mode=embedded requires the operio-bundle-embedded "
                            + "module and the H2 JDBC driver on the runtime classpath. "
                            + "The production Docker image does not include these "
                            + "dependencies. Either switch to operio.mode=production "
                            + "(recommended for deployed environments) or add "
                            + "operio-bundle-embedded as a runtime dependency.",
                    e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to instantiate EmbeddedConsentBundle via reflection. "
                            + "This is likely a classpath or version mismatch — ensure "
                            + "operio-bundle-embedded is compatible with this API version.",
                    e);
        }
    }

    // -----------------------------------------------------------------------
    // Validation helpers
    // -----------------------------------------------------------------------

    /**
     * Appends PostgreSQL SSL parameters to a JDBC URL based on the database configuration.
     *
     * <p>PostgreSQL JDBC driver expects SSL mode via the {@code sslmode} query parameter. This
     * method appends the appropriate parameters to the base URL.
     *
     * <p>Parameters added:
     *
     * <ul>
     *   <li>{@code sslmode} — always appended with the configured SSL mode ({@code disable}, {@code
     *       require}, {@code verify-ca}, {@code verify-full})
     *   <li>{@code sslcert} — appended if {@link OperioConfig.DbConfig#sslCertPath()} is configured
     *   <li>{@code sslkey} — appended if {@link OperioConfig.DbConfig#sslKeyPath()} is configured
     * </ul>
     *
     * <p><strong>Backward compatibility:</strong> If no SSL mode is configured or the URL is for an
     * embedded database (H2), no parameters are appended.
     *
     * @param baseUrl the JDBC URL (e.g. {@code jdbc:postgresql://localhost/operio})
     * @param dbConfig the database configuration including SSL settings
     * @return the URL with SSL parameters appended, or the original URL if N/A
     */
    private static String appendSslParameters(String baseUrl, OperioConfig.DbConfig dbConfig) {
        // Only apply SSL parameters to PostgreSQL URLs
        if (!baseUrl.startsWith("jdbc:postgresql://")) {
            return baseUrl;
        }

        StringBuilder urlBuilder = new StringBuilder(baseUrl);

        // Determine if URL already has query parameters
        boolean hasQueryParams = baseUrl.contains("?");
        char separator = hasQueryParams ? '&' : '?';

        // Append sslmode (always)
        urlBuilder.append(separator).append("sslmode=").append(dbConfig.sslMode());
        separator = '&'; // Subsequent parameters use &

        // Append sslcert if configured
        if (dbConfig.sslCertPath().isPresent() && !dbConfig.sslCertPath().get().isBlank()) {
            urlBuilder.append(separator).append("sslcert=").append(dbConfig.sslCertPath().get());
        }

        // Append sslkey if configured
        if (dbConfig.sslKeyPath().isPresent() && !dbConfig.sslKeyPath().get().isBlank()) {
            urlBuilder.append(separator).append("sslkey=").append(dbConfig.sslKeyPath().get());
        }

        String modifiedUrl = urlBuilder.toString();
        LOG.debug(
                "PostgreSQL JDBC URL SSL parameters: sslmode={}, sslcert={}, sslkey={}",
                dbConfig.sslMode(),
                dbConfig.sslCertPath().orElse("<not set>"),
                dbConfig.sslKeyPath().orElse("<not set>"));

        return modifiedUrl;
    }

    /**
     * Logs the effective TLS/SSL configuration for database connections at startup.
     *
     * <p>This is a fail-fast guard so that missing database credentials (empty env vars, missing
     * YAML keys) surface as a clear startup error rather than a cryptic JDBC authentication failure
     * minutes later.
     *
     * @param value the configuration value to check
     * @param keyName a human-readable config-key name for the error message
     * @throws IllegalStateException if the value is null or blank
     */
    private static void requireNonBlank(String value, String keyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "operio.mode=production requires "
                            + keyName
                            + " to be set to a non-blank value. "
                            + "Set the corresponding environment variable or "
                            + "application.yml property before starting the API.");
        }
    }

    /**
     * Validates that platform DB credentials are present and non-blank in production mode. Called
     * at the top of {@link #tenantService()} before any connections are attempted.
     */
    private void validatePlatformDbCredentials() {
        OperioConfig.DbConfig platformDb =
                config.platformDb()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "operio.mode=production requires operio.platform-db"
                                                        + " to be configured"));
        requireNonBlank(platformDb.url(), "operio.platform-db.url");
        requireNonBlank(
                platformDb.username().orElse(null),
                "operio.platform-db.username (env: OPERIO_PLATFORM_DB_USERNAME)");
        requireNonBlank(
                platformDb.password().orElse(null),
                "operio.platform-db.password (env: OPERIO_PLATFORM_DB_PASSWORD)");
    }

    private void runFlyway(DataSource ds, String location, String historyTable) {
        LOG.debug(
                "Running Flyway migrations: location={}, historyTable={}", location, historyTable);
        var result =
                Flyway.configure()
                        .dataSource(ds)
                        .locations(location)
                        .table(historyTable)
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .load()
                        .migrate();
        LOG.debug(
                "Flyway migration result: location={}, migrationsExecuted={}, success={}",
                location,
                result.migrationsExecuted,
                result.success);
    }

    private void runFlywayInSchema(
            DataSource ds, String location, String historyTable, String schema) {
        LOG.debug(
                "Running Flyway migrations: location={}, historyTable={}, schema={}",
                location,
                historyTable,
                schema);
        var result =
                Flyway.configure()
                        .dataSource(ds)
                        .locations(location)
                        .table(historyTable)
                        .schemas(schema)
                        .baselineOnMigrate(true)
                        .baselineVersion("0")
                        .load()
                        .migrate();
        LOG.debug(
                "Flyway migration result: location={}, migrationsExecuted={}, success={}",
                location,
                result.migrationsExecuted,
                result.success);
    }
}
