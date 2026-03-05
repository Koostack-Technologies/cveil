package com.koostack.operio.rest.config;

import com.koostack.operio.bundle.core.CoreConsentBundle;
import com.koostack.operio.core.DefaultPlatformService;
import com.koostack.operio.core.DefaultTenantService;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.PlatformService;
import com.koostack.operio.core.TenantService;
import com.koostack.operio.core.platform.billing.BillingCommandService;
import com.koostack.operio.core.platform.billing.BillingQueryService;
import com.koostack.operio.core.platform.core.CoreCommandService;
import com.koostack.operio.core.platform.core.CoreQueryService;
import com.koostack.operio.core.platform.iam.TokenValidationService;
import com.koostack.operio.core.platform.iam.IdpProvider;
import com.koostack.operio.core.platform.iam.IdpTenantResolver;
import com.koostack.operio.core.tenant.authz.AuthzProvider;
import com.koostack.operio.core.spi.PluginBundle;
import com.koostack.operio.platform.billing.jooq.BillingProjectionHandler;
import com.koostack.operio.platform.billing.jooq.JooqBillingCommandConfig;
import com.koostack.operio.platform.billing.jooq.JooqBillingCommandService;
import com.koostack.operio.platform.billing.jooq.JooqBillingQueryConfig;
import com.koostack.operio.platform.billing.jooq.JooqBillingQueryService;
import com.koostack.operio.platform.core.jooq.CoreProjectionHandler;
import com.koostack.operio.platform.core.jooq.JooqCoreCommandConfig;
import com.koostack.operio.platform.core.jooq.JooqCoreCommandService;
import com.koostack.operio.platform.core.jooq.JooqCoreQueryConfig;
import com.koostack.operio.platform.core.jooq.JooqCoreQueryService;
import com.koostack.operio.platform.tenant.jooq.TenantManagerProjectionHandler;
import com.koostack.operio.platform.tenant.jooq.JooqTenantManagerConfig;
import com.koostack.operio.platform.tenant.jooq.JooqTenantManagerService;
import com.koostack.operio.plugin.consent.jooq.ConsentAdminCommandConfig;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminCommandService;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminQueryService;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig;
import com.koostack.operio.plugin.event.jooq.JooqPlatformEventService;
import com.koostack.operio.plugin.event.jooq.PlatformEventJooqConfig;
import com.koostack.operio.plugin.jooq.common.JooqContextFactory;
import com.koostack.operio.plugin.jooq.common.JooqDataSourceFactory;
import com.koostack.operio.plugin.redis.common.stream.AbstractStreamConsumer;
import com.koostack.operio.plugin.redis.common.stream.StreamHandler;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisClientFactory;
import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider;
import com.koostack.operio.plugin.redis.common.pubsub.RedisInvalidationPublisher;
import com.koostack.operio.plugin.redis.common.pubsub.RedisInvalidationSubscriber;
import com.koostack.operio.core.tenant.consent.invalidation.SubscriptionManager;
import com.koostack.operio.tenant.consent.projection.admin.ConsentAdminProjectionHandler;
import com.koostack.operio.tenant.consent.projection.decision.ConsentGraphProjectionHandler;
import com.koostack.operio.tenant.consent.projection.decision.ConsentGraphRedisRepository;
import com.koostack.operio.tenant.event.jooq.TenantStreamConsumer;
import com.koostack.operio.tenant.event.jooq.JooqTenantEventService;
import com.koostack.operio.tenant.event.jooq.TenantEventJooqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.arc.ClientProxy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * CDI producer that wires the {@link Operio} instance and its key sub-services
 * from Quarkus configuration.
 *
 * <p>This is the Quarkus equivalent of the Spring Boot
 * {@code OperioAutoConfiguration} in the starter module. It reads
 * {@link OperioConfig} (SmallRye {@code @ConfigMapping}) and constructs the
 * appropriate consent bundle, platform admin service, and token verifier based
 * on the configured deployment mode.
 *
 * <h2>Production mode ({@code operio.mode=production})</h2>
 * <ul>
 *   <li>{@link CoreConsentBundle} — Postgres (jOOQ) + Redis consent stack
 *       with dual-write sync</li>
 *   <li>CQRS command/query services — tenant lifecycle, API key management,
 *       billing, and plan management</li>
 *   <li>{@code ApiKeyTokenVerifier} — SHA-256 bearer token verification
 *       against the {@code platform_iam.api_key} projection table</li>
 * </ul>
 *
 * <h2>Embedded mode ({@code operio.mode=embedded})</h2>
 * <ul>
 *   <li>{@link EmbeddedConsentBundle} — H2 in-memory + Caffeine cache,
 *       zero external infrastructure (ideal for local dev and testing)</li>
 *   <li>Platform CQRS services and token verifier are <strong>not</strong>
 *       wired — embedded mode has no multi-tenant management</li>
 * </ul>
 *
 * <h2>Produced beans</h2>
 * <ul>
 *   <li>{@link Operio} — the primary entry point for consent operations</li>
 *   <li>{@link CoreQueryService}, {@link CoreCommandService}, etc. — CQRS
 *       platform services (production only; {@code null} in embedded mode)</li>
 *   <li>{@link TokenValidationService} — bearer token verification
 *       (production only; {@code null} in embedded mode)</li>
 * </ul>
 *
 * <p>All three beans are {@code @ApplicationScoped} singletons — created
 * once at startup and shared across all request threads.
 *
 * @see OperioConfig
 * @see CoreConsentBundle
 * @see EmbeddedConsentBundle
 */
@ApplicationScoped
public class OperioProducers {

    private static final Logger LOG = LoggerFactory.getLogger(
        OperioProducers.class
    );

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
    @SuppressWarnings("rawtypes")
    private com.koostack.operio.core.platform.iam.IamCommandService iamCommandService;
    @SuppressWarnings("rawtypes")
    private com.koostack.operio.core.platform.iam.IamQueryService iamQueryService;
    private com.koostack.operio.plugin.platform.jooq.ApiKeyTokenVerifier tokenVerifier;

    @Inject
    OperioConfig config;

    @Inject
    Instance<IdpProvider<?>> idpProviderInstance;

    @Inject
    Instance<AuthzProvider<?>> authzProviderInstance;

    // -----------------------------------------------------------------------
    // Primary Operio bean
    // -----------------------------------------------------------------------

    /**
     * Produces the {@link Operio} singleton — the primary entry point for
     * all consent operations (grant, revoke, evaluate, batch, access,
     * discovery sync, health checks).
     *
     * <p>Wiring logic:
     * <ol>
     *   <li>Selects the consent bundle based on {@code operio.mode}.</li>
     *   <li>In production mode, also wires the platform admin service and
     *       token verifier from {@code operio.platform-db} and
     *       {@code operio.redis} config.</li>
     *   <li>Sets the application name if configured.</li>
     * </ol>
     *
     * @return the fully-wired {@link TenantService} instance
     * @throws IllegalStateException if required configuration is missing for
     *                               the selected mode
     */
    @Produces
    @ApplicationScoped
    public TenantService tenantService() {
        String mode = config.mode();
        LOG.info("Operio REST API: initializing (mode={})", mode);

        Operio.Builder builder = Operio.builder();

        // -- Consent bundle (wires read-path: Redis decision service) ---------
        PluginBundle consentBundle = createConsentBundle(mode);
        builder.withBundle(consentBundle);

        // -- Application name (override after bundle so config wins) ----------
        config
            .appName()
            .ifPresent(appName -> {
                if (!appName.isBlank()) {
                    LOG.info("Operio REST API: app-name={}", appName);
                    builder.withAppName(appName);
                }
            });

        // -- Token verifier (production only) ------------------------------------
        // The token verifier uses IamQueryService from the CQRS stack.
        if (MODE_PRODUCTION.equalsIgnoreCase(mode)) {
            com.koostack.operio.plugin.platform.jooq.ApiKeyTokenVerifier tokenVerifier =
                new com.koostack.operio.plugin.platform.jooq.ApiKeyTokenVerifier();
            builder.withTokenValidationService(tokenVerifier);
            this.tokenVerifier = tokenVerifier;

            LOG.info(
                "Operio REST API: token verifier wired (api-key)"
            );
        } else {
            LOG.info(
                "Operio REST API: embedded mode — token verifier not wired"
            );
        }

        // -- IdP provider (production only, when Keycloak is configured) ------
        if (idpProviderInstance.isResolvable()) {
            IdpProvider<?> idp = idpProviderInstance.get();
            if (idp != null) {
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
        JooqConsentAdminQueryService queryService = new JooqConsentAdminQueryService();

        // -- Command + query services (production: event-sourced + jOOQ) ------
        // Graph replaces Redis admin cache — JOOQ admin query used directly
        com.koostack.operio.core.tenant.consent.ConsentAdminQueryService<?> effectiveQueryService = queryService;

        if (MODE_PRODUCTION.equalsIgnoreCase(mode) && consentBundle instanceof CoreConsentBundle coreBundle) {
            // Event service for the command service (publishes to event store + Redis streams)
            JooqConsentAdminServiceConfig jooqConfig = coreBundle.jooqConfig();
            TenantEventJooqConfig eventConfig = new TenantEventJooqConfig(
                jooqConfig.connectionString(), jooqConfig.dialect(),
                jooqConfig.username(), jooqConfig.password(),
                null, null, null
            );
            // Run tenant-event schema migrations before the event service
            // tries to register event types (needs the event_type table).
            DataSource tenantEventDs = JooqDataSourceFactory.createDataSource(
                eventConfig, "operio-tenant-event-migrations"
            );
            runFlyway(tenantEventDs, "classpath:db/migration/tenant-event-postgresql", "flyway_schema_history_tenant_event");

            JooqTenantEventService eventService = new JooqTenantEventService();
            eventService.configure(eventConfig);

            commandService.configure(new ConsentAdminCommandConfig(eventService));
            queryService.configure(jooqConfig);

            LOG.info("Operio REST API: command service (event-sourced) and query service (jOOQ) wired");
        }

        // -- Platform CQRS services (production only) -------------------------
        if (MODE_PRODUCTION.equalsIgnoreCase(mode)) {
            wirePlatformCqrsServices(builder);
        }

        DefaultTenantService tenantService = builder.buildTenantService(
            commandService, effectiveQueryService
        );

        // -- DSAR key encryptor (envelope encryption for per-subject keys) ----
        if (MODE_PRODUCTION.equalsIgnoreCase(mode)) {
            config.platformDb().ifPresent(platformDb -> {
                String encKey = platformDb.encryptionKey()
                    .filter(k -> !k.isBlank()).orElse(null);
                if (encKey != null) {
                    tenantService.setKeyEncryptor(
                        raw -> com.koostack.operio.plugin.platform.jooq.crypto.FieldEncryptor
                            .encrypt(raw, encKey));
                }
            });
        }

        LOG.info(
            "Operio REST API: initialized successfully (appName={})",
            tenantService.appName()
        );
        return tenantService;
    }

    // -----------------------------------------------------------------------
    // Sub-service producers
    // -----------------------------------------------------------------------

    /**
    @Produces
    @ApplicationScoped
    @SuppressWarnings({"unchecked", "rawtypes"})
    public CoreCommandService coreCommandService(TenantService tenantService) {
        TenantService unwrapped = ClientProxy.unwrap(tenantService);
        if (unwrapped instanceof DefaultTenantService dts) {
            CoreCommandService service = dts.coreCommandService();
            if (service != null) {
                LOG.debug("Operio REST API: CoreCommandService available");
                return service;
            }
        }
        LOG.debug("Operio REST API: CoreCommandService not available (embedded mode)");
        return null;
    }

    @Produces
    @ApplicationScoped
    @SuppressWarnings({"unchecked", "rawtypes"})
    public CoreQueryService coreQueryService(TenantService tenantService) {
        TenantService unwrapped = ClientProxy.unwrap(tenantService);
        if (unwrapped instanceof DefaultTenantService dts) {
            CoreQueryService service = dts.coreQueryService();
            if (service != null) {
                LOG.debug("Operio REST API: CoreQueryService available");
                return service;
            }
        }
        LOG.debug("Operio REST API: CoreQueryService not available (embedded mode)");
        return null;
    }

    @Produces
    @ApplicationScoped
    @SuppressWarnings("rawtypes")
    public com.koostack.operio.core.platform.iam.IamCommandService iamCommandService() {
        if (iamCommandService != null) {
            LOG.debug("Operio REST API: IamCommandService available");
            return iamCommandService;
        }
        LOG.debug("Operio REST API: IamCommandService not available (embedded mode)");
        return null;
    }

    @Produces
    @ApplicationScoped
    @SuppressWarnings("rawtypes")
    public com.koostack.operio.core.platform.iam.IamQueryService iamQueryService() {
        if (iamQueryService != null) {
            LOG.debug("Operio REST API: IamQueryService available");
            return iamQueryService;
        }
        LOG.debug("Operio REST API: IamQueryService not available (embedded mode)");
        return null;
    }

    @Produces
    @ApplicationScoped
    @SuppressWarnings("rawtypes")
    public com.koostack.operio.core.platform.plan.PlanQueryService planQueryService() {
        if (planQueryService != null) {
            LOG.debug("Operio REST API: PlanQueryService available");
            return planQueryService;
        }
        LOG.debug("Operio REST API: PlanQueryService not available (embedded mode)");
        return null;
    }

    /**
     * Produces the {@link TokenValidationService} singleton — used by
     * {@code ApiKeyAuthFilter} to authenticate bearer tokens on every
     * request.
     *
     * <p>In production mode this returns the {@code ApiKeyTokenVerifier}
     * wired during {@link #operio()} construction. In embedded mode it
     * returns {@code null}.
     *
     * @param tenantService the built {@link TenantService} instance (injected by CDI)
     * @return the token validation service, or {@code null} in embedded mode
     */
    @Produces
    @ApplicationScoped
    @SuppressWarnings("rawtypes")
    public TokenValidationService tokenValidationService(TenantService tenantService) {
        TenantService unwrapped = ClientProxy.unwrap(tenantService);
        if (unwrapped instanceof DefaultTenantService dts) {
            TokenValidationService service = dts.tokenValidationService();
            if (service != null) {
                LOG.debug("Operio REST API: TokenValidationService available");
                return service;
            }
        }
        LOG.debug(
            "Operio REST API: TokenValidationService not available (embedded mode)"
        );
        return null;
    }

    /**
     * Produces the {@link IdpProvider} singleton — the platform default
     * identity provider (Keycloak).
     *
     * <p>In production mode with Keycloak configured, this returns a
     * {@code KeycloakIdpProvider} connected to the configured Keycloak
     * instance. In embedded mode or when Keycloak is not configured,
     * returns {@code null}.
     */
    @Produces
    @ApplicationScoped
    @SuppressWarnings("rawtypes")
    public IdpProvider idpProvider() {
        if (!MODE_PRODUCTION.equalsIgnoreCase(config.mode())) {
            LOG.debug("Operio REST API: IdpProvider not available (embedded mode)");
            return null;
        }

        return config.keycloak()
            .filter(kc -> kc.url().isPresent() && !kc.url().get().isBlank())
            .map(kc -> {
            com.koostack.operio.plugin.platform.keycloak.KeycloakConfig kcConfig =
                new com.koostack.operio.plugin.platform.keycloak.KeycloakConfig(
                    kc.url().get(),
                    "master",
                    "admin-cli",
                    kc.adminUsername(),
                    kc.adminPassword().orElse(""),
                    kc.clientId(),
                    kc.clientSecret().orElse(null)
                );
            com.koostack.operio.plugin.platform.keycloak.KeycloakIdpProvider provider =
                new com.koostack.operio.plugin.platform.keycloak.KeycloakIdpProvider(kcConfig);
            LOG.info("Operio REST API: KeycloakIdpProvider wired (url={})", kc.url().get());
            return (IdpProvider) provider;
        }).orElseGet(() -> {
            LOG.info("Operio REST API: No Keycloak configuration — IdpProvider not available");
            return null;
        });
    }

    /**
     * Produces the {@link AuthzProvider} singleton — tenant-scoped
     * authorization (role management) backed by Keycloak.
     *
     * <p>In production mode with Keycloak configured, returns a
     * {@code KeycloakAuthzProvider}. In embedded mode or when Keycloak
     * is not configured, returns {@code null}.
     */
    @Produces
    @ApplicationScoped
    @SuppressWarnings("rawtypes")
    public AuthzProvider authzProvider() {
        if (!MODE_PRODUCTION.equalsIgnoreCase(config.mode())) {
            LOG.debug("Operio REST API: AuthzProvider not available (embedded mode)");
            return null;
        }

        return config.keycloak()
            .filter(kc -> kc.url().isPresent() && !kc.url().get().isBlank())
            .map(kc -> {
            com.koostack.operio.tenant.authz.keycloak.KeycloakAuthzConfig authzConfig =
                new com.koostack.operio.tenant.authz.keycloak.KeycloakAuthzConfig(
                    kc.url().get(),
                    "master",
                    "admin-cli",
                    kc.adminUsername(),
                    kc.adminPassword().orElse("")
                );
            com.koostack.operio.tenant.authz.keycloak.KeycloakAuthzProvider provider =
                new com.koostack.operio.tenant.authz.keycloak.KeycloakAuthzProvider(authzConfig);
            LOG.info("Operio REST API: KeycloakAuthzProvider wired (url={})", kc.url().get());
            return (AuthzProvider) provider;
        }).orElseGet(() -> {
            LOG.info("Operio REST API: No Keycloak configuration — AuthzProvider not available");
            return null;
        });
    }

    /**
     * Produces the {@link IdpTenantResolver} singleton — resolves which
     * {@link IdpProvider} to use for a given tenant.
     *
     * <p>Falls back to the platform default (Keycloak) when no
     * per-tenant IdP override is configured.
     */
    @Produces
    @ApplicationScoped
    public IdpTenantResolver idpTenantResolver(Instance<IdpProvider<?>> defaultIdpProviderInstance) {
        if (!defaultIdpProviderInstance.isResolvable()) {
            LOG.debug("Operio REST API: IdpTenantResolver not available (no default IdP)");
            return null;
        }
        IdpProvider<?> defaultIdpProvider = defaultIdpProviderInstance.get();
        if (defaultIdpProvider == null) {
            LOG.debug("Operio REST API: IdpTenantResolver not available (IdP returned null)");
            return null;
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

    /**
     * Produces the {@link PlatformService} singleton — handles cross-tenant
     * IAM operations (user registration, email verification, password reset).
     *
     * <p>In production mode with an IdpProvider configured, returns a
     * {@link DefaultPlatformService} backed by the IdpProvider. In embedded
     * mode or when no IdpProvider is available, returns {@code null}.
     */
    @Produces
    @ApplicationScoped
    public PlatformService platformService(TenantService tenantService) {
        // Force TenantService initialization — this triggers wirePlatformCqrsServices()
        // which sets coreCommandService, billingCommandService, etc. on this producer bean.
        // Without this call, the proxy would remain uninitialized.
        String appName = tenantService.appName();
        LOG.info("Operio REST API: platformService() — TenantService initialized (appName={}), coreCommandService={}",
            appName, coreCommandService);

        IdpProvider<?> idp = null;
        if (idpProviderInstance.isResolvable()) {
            try {
                idp = idpProviderInstance.get();
            } catch (Exception e) {
                LOG.debug("Operio REST API: IdpProvider not available: {}", e.getMessage());
            }
        }

        // PlatformService can operate without IdpProvider (createTenant doesn't need it)
        if (idp == null && coreCommandService == null) {
            LOG.debug("Operio REST API: PlatformService not available (no IdpProvider and no CQRS services)");
            return null;
        }

        String resolvedAppName = config.appName().orElse(Operio.DEFAULT_APP_NAME);
        PlatformService service = new DefaultPlatformService(
            resolvedAppName, idp, coreCommandService, billingCommandService,
            coreQueryService, planQueryService, null, null);
        LOG.info("Operio REST API: PlatformService wired (DefaultPlatformService, idp={}, coreCmd={}, billingCmd={}, planQuery={})",
            idp != null, coreCommandService != null, billingCommandService != null, planQueryService != null);
        return service;
    }

    // -----------------------------------------------------------------------
    // Per-tenant stream consumer (production only)
    // -----------------------------------------------------------------------

    private TenantStreamConsumer tenantStreamConsumer;
    private AbstractStreamConsumer platformStreamConsumer;
    private SubscriptionManager adminSubscriptionManager;
    private com.koostack.operio.tenant.consent.projection.decision.ExpiredConsentReaper expiredConsentReaper;

    /**
     * Produces the {@link TenantStreamConsumer} that runs both the consent
     * decision projection and admin projection — materializing domain
     * events into the Redis decision cache and SQL admin tables respectively.
     *
     * <p>Only activated in production mode (when {@code adminRedisPool} and
     * {@code tenantAwareJedisProvider} are available). In embedded mode this
     * returns {@code null} (no stream consumer needed).
     *
     * <p>The consumer is NOT started here — tenants are added dynamically
     * by {@link TenantManager} during provisioning and startup reconciliation.
     * Shutdown is handled by the {@link #onShutdown} CDI lifecycle callback.
     *
     * @param tenantService the built {@link TenantService} instance (triggers bundle initialization first)
     * @return the tenant stream consumer, or {@code null} in embedded mode
     */
    @Produces
    @ApplicationScoped
    public TenantStreamConsumer tenantStreamConsumer(TenantService tenantService) {
        if (adminRedisPool == null || tenantAwareJedisProvider == null) {
            LOG.info("Operio REST API: tenant stream consumer not wired (embedded mode)");
            return null;
        }

        // Graph projection handler — replaces both the old decision projection
        // and admin Redis projection with a unified graph-based handler
        ConsentGraphRedisRepository graphRepo =
            new ConsentGraphRedisRepository(tenantAwareJedisProvider);

        RedisInvalidationPublisher invalidationPublisher =
            new RedisInvalidationPublisher(adminRedisPool, new ObjectMapper().registerModule(new JavaTimeModule()));

        ConsentGraphProjectionHandler graphHandler =
            new ConsentGraphProjectionHandler(graphRepo, invalidationPublisher);

        // Expired consent reaper — periodically GCs consent edges past expiresAt
        if (coreQueryService != null) {
            @SuppressWarnings("unchecked")
            CoreQueryService<?> typedCqs = coreQueryService;
            java.util.function.Supplier<java.util.List<String>> tenantDiscovery = () -> {
                try {
                    return typedCqs.listTenants().stream()
                            .map(t -> t.tenantId())
                            .toList();
                } catch (Exception e) {
                    LOG.warn("Reaper tenant discovery failed", e);
                    return java.util.List.of();
                }
            };
            this.expiredConsentReaper = new com.koostack.operio.tenant.consent.projection.decision.ExpiredConsentReaper(
                    graphRepo, invalidationPublisher, tenantDiscovery);
            this.expiredConsentReaper.start();
            LOG.info("Operio REST API: ExpiredConsentReaper started");
        } else {
            LOG.warn("Operio REST API: ExpiredConsentReaper NOT started — CoreQueryService unavailable");
        }

        // Admin projection handler — uses the consent DB config to create a
        // tenant-aware DSLContext that routes to per-tenant schemas via
        // TenantContext + TenantAwareExecuteListener.
        OperioConfig.DbConfig db = config.db().orElse(null);
        DSLContext tenantDsl = null;
        if (db != null) {
            String dbUrl = appendSslParameters(db.url(), db);
            JooqConsentAdminServiceConfig consentDbConfig = new JooqConsentAdminServiceConfig(
                dbUrl, db.dialect(), db.username().orElse(null), db.password().orElse(null));
            tenantDsl = com.koostack.operio.plugin.jooq.common.JooqContextFactory
                .createTenantAwareContext(consentDbConfig, "operio-admin-projection");
        }

        // Build consumer with handlers
        TenantStreamConsumer consumer = new TenantStreamConsumer(
            JedisProvider.shared(adminRedisPool), 2000L, 100);
        consumer.registerHandler("graph-projection", graphHandler);

        if (tenantDsl != null) {
            ConsentAdminProjectionHandler adminHandler = new ConsentAdminProjectionHandler(tenantDsl);
            consumer.registerHandler("admin-projection", adminHandler);
            LOG.info("Operio REST API: admin projection handler wired (group=admin-projection)");
        } else {
            LOG.warn("Operio REST API: admin projection handler NOT wired — consent DB config missing");
        }

        // SSE publisher: reads from Redis Pub/Sub and publishes to SSE clients.
        // Channel prefix must match RedisInvalidationPublisher so that
        // mutations flow through Redis → SSE → client.
        ObjectMapper adminRedisMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try {
            RedisInvalidationSubscriber adminSubscriber =
                new RedisInvalidationSubscriber(adminRedisPool, adminRedisMapper,
                    "operio:consent-cache-invalidation:");
            this.adminSubscriptionManager = new SubscriptionManager(adminSubscriber);
            LOG.info("Operio REST API: SSE publisher wired (channel=consent-cache-invalidation)");
        } catch (Exception e) {
            LOG.warn("Operio REST API: SSE publisher NOT wired — Redis Pub/Sub init failed", e);
        }

        this.tenantStreamConsumer = consumer;

        LOG.info("Operio REST API: tenant stream consumer created (groups=graph-projection,admin-projection)");
        return consumer;
    }

    /**
     * Produces the admin {@link SubscriptionManager} that bridges Redis
     * Pub/Sub invalidation signals to SSE endpoints for consent admin
     * aggregate changes.
     */
    @Produces
    @ApplicationScoped
    @Named("adminSubscriptionManager")
    public SubscriptionManager adminSubscriptionManager() {
        return adminSubscriptionManager;
    }

    /**
     * CDI shutdown hook — stops the stream consumer gracefully.
     */
    public void onShutdown(@jakarta.enterprise.event.Observes
                           @jakarta.enterprise.context.BeforeDestroyed(ApplicationScoped.class)
                           Object event) {
        if (tenantStreamConsumer != null) {
            LOG.info("Operio REST API: stopping tenant stream consumer");
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
     * Creates and wires all platform CQRS services: event service, command
     * services, query services, projection handlers, and the platform stream
     * consumer.
     *
     * <p>Called once during {@link #tenantService()} initialization in
     * production mode. The command services are stored as instance fields
     * so that {@link #platformService()} can pass them to
     * {@link DefaultPlatformService}.
     */
    private void wirePlatformCqrsServices(Operio.Builder builder) {
        OperioConfig.DbConfig platformDb = config.platformDb().orElse(null);
        if (platformDb == null) {
            LOG.warn("Operio REST API: platform CQRS services NOT wired — operio.platform-db not configured");
            return;
        }

        String platformDbUrl = appendSslParameters(platformDb.url(), platformDb);
        String appName = config.appName().orElse(Operio.DEFAULT_APP_NAME);

        // -- Redis config (optional) ------------------------------------------
        RedisPluginConfig redisPluginConfig = config
            .redis()
            .<RedisPluginConfig>map(redis -> {
                String pw = redis.password().filter(p -> !p.isBlank()).orElse(null);
                RedisClientFactory.TlsConfig tls = redis.tlsEnabled()
                    ? new RedisClientFactory.TlsConfig(
                        true,
                        redis.tlsCaCertPath().orElse(null),
                        redis.tlsVerifyPeer())
                    : null;
                return new RedisPluginConfig() {
                    @Override public String host() { return redis.host(); }
                    @Override public int port() { return redis.port(); }
                    @Override public String password() { return pw; }
                    @Override public RedisClientFactory.TlsConfig tlsConfig() { return tls; }
                };
            })
            .orElse(null);

        // -- Platform event service -------------------------------------------
        PlatformEventJooqConfig eventConfig = new PlatformEventJooqConfig(
            platformDbUrl,
            platformDb.dialect(),
            platformDb.username().orElse(null),
            platformDb.password().orElse(null),
            redisPluginConfig
        );

        // Run CQRS schema migrations (event store + domain schemas).
        // The event-sourced CQRS modules need their own migrations.
        DataSource migrationDs = JooqDataSourceFactory.createDataSource(
            eventConfig, "operio-cqrs-migrations"
        );

        // Platform event store lives in t_platform schema (TenantAwareExecuteListener
        // sets search_path to t_platform for platform events).
        try (var conn = migrationDs.getConnection()) {
            conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS t_platform");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create t_platform schema", e);
        }
        runFlywayInSchema(migrationDs, "classpath:db/migration/platform-event-postgresql",
            "flyway_schema_history_platform_event", "t_platform");

        // Domain projection schemas (self-contained — each V1 creates its own schema)
        runFlyway(migrationDs, "classpath:db/migration/platform-core", "flyway_schema_history_platform_core");
        runFlyway(migrationDs, "classpath:db/migration/platform-billing", "flyway_schema_history_platform_billing");
        runFlyway(migrationDs, "classpath:db/migration/platform-iam", "flyway_schema_history_platform_iam");

        JooqPlatformEventService platformEventService = new JooqPlatformEventService();
        platformEventService.configure(eventConfig);

        // -- Command services -------------------------------------------------
        JooqCoreCommandService coreCmdSvc = new JooqCoreCommandService();
        coreCmdSvc.configure(new JooqCoreCommandConfig(platformEventService));

        JooqBillingCommandService billingCmdSvc = new JooqBillingCommandService();
        billingCmdSvc.configure(new JooqBillingCommandConfig(platformEventService));

        com.koostack.operio.platform.iam.jooq.JooqIamCommandService iamCmdSvc =
            new com.koostack.operio.platform.iam.jooq.JooqIamCommandService();
        iamCmdSvc.configure(new com.koostack.operio.platform.iam.jooq.JooqIamCommandConfig(platformEventService));

        this.coreCommandService = coreCmdSvc;
        this.billingCommandService = billingCmdSvc;
        this.iamCommandService = iamCmdSvc;

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
            "SET search_path TO platform_core, platform_billing, platform_iam, t_platform, public"
        );
        DataSource platformDataSource = new com.zaxxer.hikari.HikariDataSource(hikariCfg);
        DSLContext platformDsl = DSL.using(
            platformDataSource, SQLDialect.POSTGRES,
            new org.jooq.conf.Settings()
                .withRenderNameCase(org.jooq.conf.RenderNameCase.LOWER)
                .withRenderSchema(false)
        );

        // -- Query services ---------------------------------------------------
        // Build a decryptor for encrypted DB fields (e.g. redis_acl_password).
        String encryptionKey = platformDb.encryptionKey()
            .filter(k -> !k.isBlank()).orElse(null);
        java.util.function.UnaryOperator<String> fieldDecryptor = encryptionKey != null
            ? raw -> com.koostack.operio.plugin.platform.jooq.crypto.FieldEncryptor
                .decryptIfKeyPresent(raw, encryptionKey)
            : java.util.function.UnaryOperator.identity();

        JooqCoreQueryService coreQuerySvc = new JooqCoreQueryService();
        coreQuerySvc.configure(new JooqCoreQueryConfig(platformDsl, fieldDecryptor));

        JooqBillingQueryService billingQuerySvc = new JooqBillingQueryService();
        billingQuerySvc.configure(new JooqBillingQueryConfig(platformDsl));

        com.koostack.operio.platform.plan.jooq.JooqPlanQueryService planQuerySvc =
            new com.koostack.operio.platform.plan.jooq.JooqPlanQueryService();
        planQuerySvc.configure(new com.koostack.operio.platform.plan.jooq.JooqPlanQueryConfig(platformDsl));

        this.coreQueryService = coreQuerySvc;
        this.planQueryService = planQuerySvc;

        com.koostack.operio.platform.iam.jooq.JooqIamQueryService iamQuerySvc =
            new com.koostack.operio.platform.iam.jooq.JooqIamQueryService();
        iamQuerySvc.configure(new com.koostack.operio.platform.iam.jooq.JooqIamQueryConfig(platformDsl));
        this.iamQueryService = iamQuerySvc;

        // Wire the token verifier with the CQRS IamQueryService so it reads
        // from platform_iam.api_key instead of the old public.api_key.
        if (this.tokenVerifier != null) {
            this.tokenVerifier.setIamQueryService(iamQuerySvc);
            LOG.info("Operio REST API: ApiKeyTokenVerifier wired with IamQueryService");
        }

        builder.withCoreQueryService(coreQuerySvc);
        builder.withBillingQueryService(billingQuerySvc);

        LOG.info("Operio REST API: platform command + query services wired");

        // -- Projection handlers + stream consumer (requires Redis) -----------
        if (redisPluginConfig == null || adminRedisPool == null) {
            LOG.warn("Operio REST API: platform stream consumer NOT wired — Redis not configured");
            return;
        }

        CoreProjectionHandler coreProjection = new CoreProjectionHandler(platformDsl);
        BillingProjectionHandler billingProjection = new BillingProjectionHandler(platformDsl);
        com.koostack.operio.platform.iam.jooq.IamProjectionHandler iamProjection =
            new com.koostack.operio.platform.iam.jooq.IamProjectionHandler(platformDsl);

        // Tenant manager projection handler — provisions infra on TenantProvisioningStarted
        DataSource tenantMgrDataSource = JooqDataSourceFactory.createDataSource(
            eventConfig, "operio-tenant-manager"
        );
        JooqTenantManagerService tenantManagerSvc = new JooqTenantManagerService();
        tenantManagerSvc.configure(new JooqTenantManagerConfig(
            platformDsl, tenantMgrDataSource, null,
            redisPluginConfig, appName, "classpath:db/migration/consent"
        ));

        TenantManagerProjectionHandler tenantMgrProjection =
            new TenantManagerProjectionHandler(tenantManagerSvc, coreCmdSvc, iamCmdSvc);

        // Startup reconciliation — run Flyway migrations for all existing
        // active tenants via the new CQRS TenantManagerService.
        tenantManagerSvc.migrateAllTenants();
        LOG.info("Operio REST API: startup reconciliation complete — tenant schemas migrated");

        // -- Platform stream consumer -----------------------------------------
        AbstractStreamConsumer consumer = new AbstractStreamConsumer(
                JedisProvider.shared(adminRedisPool), 2000L, 100) {
            @Override
            protected String streamKeyForTenant(String tenantId) {
                // Must match the hardcoded "operio" prefix in
                // JooqPlatformEventService → RedisEventPublisher
                return "operio:platform-events";
            }
        };

        consumer.registerHandler("core-projection", (StreamHandler) coreProjection::onEvent);
        consumer.registerHandler("billing-projection", (StreamHandler) billingProjection::onEvent);
        consumer.registerHandler("iam-projection", (StreamHandler) iamProjection::onEvent);
        consumer.registerHandler("tenant-manager-projection", (StreamHandler) tenantMgrProjection::onEvent);
        consumer.addTenant("platform");

        this.platformStreamConsumer = consumer;
        LOG.info("Operio REST API: platform stream consumer started " +
            "(groups=core-projection,billing-projection,iam-projection,tenant-manager-projection)");
    }

    // -----------------------------------------------------------------------
    // Internal wiring helpers
    // -----------------------------------------------------------------------

    /**
     * Creates the appropriate consent bundle based on the deployment mode.
     *
     * @param mode the deployment mode ({@code "production"} or
     *             {@code "embedded"})
     * @return the consent bundle
     * @throws IllegalStateException if the mode is unknown or required
     *                               configuration is missing
     */
    private PluginBundle createConsentBundle(String mode) {
        if (MODE_PRODUCTION.equalsIgnoreCase(mode)) {
            return createCoreConsentBundle();
        }
        if (MODE_EMBEDDED.equalsIgnoreCase(mode)) {
            return createEmbeddedConsentBundle();
        }
        throw new IllegalStateException(
            "Unknown operio.mode: '" +
                mode +
                "'. " +
                "Valid values are 'production' and 'embedded'."
        );
    }

    /**
     * Creates a {@link CoreConsentBundle} (Postgres + Redis) from the
     * {@code operio.db} and {@code operio.redis} configuration.
     *
     * @return the production consent bundle
     * @throws IllegalStateException if {@code operio.db} or
     *                               {@code operio.redis} is not configured
     */
    /**
     * Holds the {@link TenantAwareJedisProvider} created during consent
     * bundle construction so it can be shared with the
     * {@link com.koostack.operio.rest.ratelimit.RateLimitService} and
     * shut down cleanly.
     */
    private TenantAwareJedisProvider tenantAwareJedisProvider;

    /**
     * Holds the admin Redis pool created during consent bundle construction
     * so it can be shared with the invalidation publisher and stream consumer.
     */
    private JedisPooled adminRedisPool;

    private CoreConsentBundle createCoreConsentBundle() {
        OperioConfig.DbConfig db = config
            .db()
            .orElseThrow(() ->
                new IllegalStateException(
                    "operio.mode=production requires operio.db.url to be configured"
                )
            );
        OperioConfig.RedisConfig redis = config
            .redis()
            .orElseThrow(() ->
                new IllegalStateException(
                    "operio.mode=production requires operio.redis to be configured"
                )
            );

        // -- Validate credentials are present and non-blank -------------------
        requireNonBlank(db.url(), "operio.db.url");
        requireNonBlank(
            db.username().orElse(null),
            "operio.db.username (env: OPERIO_DB_USERNAME)"
        );
        requireNonBlank(
            db.password().orElse(null),
            "operio.db.password (env: OPERIO_DB_PASSWORD)"
        );

        // Append SSL parameters to JDBC URL if needed
        String dbUrl = appendSslParameters(db.url(), db);

        JooqConsentAdminServiceConfig jooqConfig =
            new JooqConsentAdminServiceConfig(
                dbUrl,
                db.dialect(),
                db.username().orElse(null),
                db.password().orElse(null)
            );

        String appName = config.appName().orElse(null);

        LOG.info(
            "Operio REST API: configuring CoreConsentBundle " +
                "(db={}, redis={}:{}, appName={}, tenantAclIsolation=enabled, db-sslmode={})",
            db.url(),
            redis.host(),
            redis.port(),
            appName != null ? appName : "<default>",
            db.sslMode()
        );

        // -- Build the admin Redis pool (default user) -----------------------
        // This pool is used for platform-level operations (provisioning,
        // health checks, migrations) where no tenant context is active.

        // Create TLS config if enabled
        RedisClientFactory.TlsConfig redisTlsConfig = null;
        if (redis.tlsEnabled()) {
            redisTlsConfig = new RedisClientFactory.TlsConfig(
                true,
                redis.tlsCaCertPath().orElse(null),
                redis.tlsVerifyPeer()
            );
            LOG.info(
                "Operio REST API: Redis TLS ENABLED " +
                    "(host={}, port={}, verify-peer={})",
                redis.host(),
                redis.port(),
                redis.tlsVerifyPeer()
            );
        } else {
            LOG.warn(
                "Operio REST API: Redis TLS DISABLED — connections are UNENCRYPTED. " +
                    "This is insecure for production deployments. " +
                    "Set operio.redis.tls-enabled=true and configure TLS ports. " +
                    "Required for HIPAA §164.312(a)(2)(ii) and GDPR Article 32 compliance."
            );
        }

        String redisPw = redis
            .password()
            .filter(pw -> !pw.isBlank())
            .orElse(null);
        JedisPooled adminPool = RedisClientFactory.createClient(
            redis.host(),
            redis.port(),
            redisPw,
            redisTlsConfig
        );
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
        this.tenantAwareJedisProvider = createTenantAwareJedisProvider(
            redis,
            adminPool,
            redisTlsConfig
        );

        if (appName != null && !appName.isBlank()) {
            return new CoreConsentBundle(
                appName,
                jooqConfig,
                tenantAwareJedisProvider
            );
        }
        return new CoreConsentBundle(
            null,
            jooqConfig,
            tenantAwareJedisProvider
        );
    }

    /**
     * Creates the {@link TenantAwareJedisProvider} that powers per-tenant
     * Redis ACL isolation.
     *
     * <p>The provider caches per-tenant {@link JedisPooled} connections,
     * each authenticated as the tenant's dedicated Redis ACL user. The
     * credential lookup delegates to
     * {@link CoreQueryService#getTenantCredentials(String)} which reads
     * from the {@code platform_core.tenant} table.
     *
     * @param redis         the Redis connection config (host, port)
     * @param adminPool     the admin-level JedisPooled (default user)
     * @param tlsConfig     the TLS configuration (null if TLS is disabled)
     * @return the tenant-aware Jedis provider
     */
    private TenantAwareJedisProvider createTenantAwareJedisProvider(
        OperioConfig.RedisConfig redis,
        JedisPooled adminPool,
        RedisClientFactory.TlsConfig tlsConfig
    ) {
        TenantAwareJedisProvider.TenantCredentialLookup lookup = tenantId -> {
            @SuppressWarnings("rawtypes")
            CoreQueryService cqs = coreQueryService;
            if (cqs == null) {
                LOG.error(
                    "Tenant '{}' credential lookup failed — CoreQueryService " +
                        "is not yet initialized (startup ordering issue)",
                    tenantId
                );
                return null;
            }
            var creds = cqs.getTenantCredentials(tenantId);
            if (creds == null) {
                LOG.warn(
                    "Tenant '{}' not found or has no Redis ACL credentials. " +
                        "Run TenantManager.reprovisionRedisAcl() to generate " +
                        "credentials for this tenant.",
                    tenantId
                );
                return null;
            }
            return new TenantAwareJedisProvider.TenantRedisCredentials(
                creds.redisAclUser(),
                creds.redisAclPassword()
            );
        };

        LOG.info(
            "Operio REST API: TenantAwareJedisProvider created " +
                "(redis={}:{}, per-tenant ACL isolation enabled)",
            redis.host(),
            redis.port()
        );

        return new TenantAwareJedisProvider(
            redis.host(),
            redis.port(),
            adminPool,
            lookup,
            tlsConfig
        );
    }

    /**
     * Creates an {@code EmbeddedConsentBundle} (H2 + Caffeine) for local
     * development and testing.
     *
     * <p>Uses default H2 in-memory settings. The application name (if
     * configured) is set on the {@link Operio.Builder} after the bundle is
     * applied — see {@link #operio()} — rather than passed to the bundle
     * constructor, avoiding a compile-time dependency on
     * {@code AnnotationDiscoveryConfig}.
     *
     * <p><strong>Reflection-based loading:</strong> The embedded bundle is
     * loaded via {@link Class#forName} so that the
     * {@code operio-bundle-embedded} module is not required on the
     * production runtime classpath. The production Docker image ships
     * without the embedded bundle (and without the H2 JDBC driver). If
     * someone starts the API with {@code operio.mode=embedded} but the
     * module is not on the classpath, a clear error message is thrown
     * explaining how to fix it.
     *
     * @return the embedded consent bundle
     * @throws IllegalStateException if the embedded bundle classes are not
     *                               on the classpath
     */
    private PluginBundle createEmbeddedConsentBundle() {
        LOG.info(
            "Operio REST API: configuring EmbeddedConsentBundle " +
                "(H2 + Caffeine, appName={})",
            config
                .appName()
                .filter(n -> !n.isBlank())
                .orElse("<default>")
        );

        try {
            Class<?> cls = Class.forName(
                "com.koostack.operio.bundle.embedded.EmbeddedConsentBundle"
            );
            return (PluginBundle) cls.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "operio.mode=embedded requires the operio-bundle-embedded " +
                    "module and the H2 JDBC driver on the runtime classpath. " +
                    "The production Docker image does not include these " +
                    "dependencies. Either switch to operio.mode=production " +
                    "(recommended for deployed environments) or add " +
                    "operio-bundle-embedded as a runtime dependency.",
                e
            );
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Failed to instantiate EmbeddedConsentBundle via reflection. " +
                    "This is likely a classpath or version mismatch — ensure " +
                    "operio-bundle-embedded is compatible with this API version.",
                e
            );
        }
    }

    // -----------------------------------------------------------------------
    // Validation helpers
    // -----------------------------------------------------------------------

    /**
     * Appends PostgreSQL SSL parameters to a JDBC URL based on the database configuration.
     *
     * <p>PostgreSQL JDBC driver expects SSL mode via the {@code sslmode} query
     * parameter. This method appends the appropriate parameters to the base URL.
     *
     * <p>Parameters added:
     * <ul>
     *   <li>{@code sslmode} — always appended with the configured SSL mode
     *       ({@code disable}, {@code require}, {@code verify-ca}, {@code verify-full})</li>
     *   <li>{@code sslcert} — appended if {@link OperioConfig.DbConfig#sslCertPath()}
     *       is configured</li>
     *   <li>{@code sslkey} — appended if {@link OperioConfig.DbConfig#sslKeyPath()}
     *       is configured</li>
     * </ul>
     *
     * <p><strong>Backward compatibility:</strong> If no SSL mode is configured
     * or the URL is for an embedded database (H2), no parameters are appended.
     *
     * @param baseUrl  the JDBC URL (e.g. {@code jdbc:postgresql://localhost/operio})
     * @param dbConfig the database configuration including SSL settings
     * @return the URL with SSL parameters appended, or the original URL if N/A
     */
    private static String appendSslParameters(
        String baseUrl,
        OperioConfig.DbConfig dbConfig
    ) {
        // Only apply SSL parameters to PostgreSQL URLs
        if (!baseUrl.startsWith("jdbc:postgresql://")) {
            return baseUrl;
        }

        StringBuilder urlBuilder = new StringBuilder(baseUrl);

        // Determine if URL already has query parameters
        boolean hasQueryParams = baseUrl.contains("?");
        char separator = hasQueryParams ? '&' : '?';

        // Append sslmode (always)
        urlBuilder
            .append(separator)
            .append("sslmode=")
            .append(dbConfig.sslMode());
        separator = '&'; // Subsequent parameters use &

        // Append sslcert if configured
        if (
            dbConfig.sslCertPath().isPresent() &&
            !dbConfig.sslCertPath().get().isBlank()
        ) {
            urlBuilder
                .append(separator)
                .append("sslcert=")
                .append(dbConfig.sslCertPath().get());
        }

        // Append sslkey if configured
        if (
            dbConfig.sslKeyPath().isPresent() &&
            !dbConfig.sslKeyPath().get().isBlank()
        ) {
            urlBuilder
                .append(separator)
                .append("sslkey=")
                .append(dbConfig.sslKeyPath().get());
        }

        String modifiedUrl = urlBuilder.toString();
        LOG.debug(
            "PostgreSQL JDBC URL SSL parameters: sslmode={}, sslcert={}, sslkey={}",
            dbConfig.sslMode(),
            dbConfig.sslCertPath().orElse("<not set>"),
            dbConfig.sslKeyPath().orElse("<not set>")
        );

        return modifiedUrl;
    }

    /**
     * Logs the effective TLS/SSL configuration for database connections at startup.
     *
     * <p>This is a fail-fast guard so that missing database credentials
     * (empty env vars, missing YAML keys) surface as a clear startup error
     * rather than a cryptic JDBC authentication failure minutes later.
     *
     * @param value   the configuration value to check
     * @param keyName a human-readable config-key name for the error message
     * @throws IllegalStateException if the value is null or blank
     */
    private static void requireNonBlank(String value, String keyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "operio.mode=production requires " +
                    keyName +
                    " to be set to a non-blank value. " +
                    "Set the corresponding environment variable or " +
                    "application.yml property before starting the API."
            );
        }
    }

    private void runFlyway(DataSource ds, String location, String historyTable) {
        LOG.debug("Running Flyway migrations: location={}, historyTable={}", location, historyTable);
        var result = Flyway.configure()
            .dataSource(ds)
            .locations(location)
            .table(historyTable)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();
        LOG.debug("Flyway migration result: location={}, migrationsExecuted={}, success={}",
            location, result.migrationsExecuted, result.success);
    }

    private void runFlywayInSchema(DataSource ds, String location, String historyTable, String schema) {
        LOG.debug("Running Flyway migrations: location={}, historyTable={}, schema={}", location, historyTable, schema);
        var result = Flyway.configure()
            .dataSource(ds)
            .locations(location)
            .table(historyTable)
            .schemas(schema)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();
        LOG.debug("Flyway migration result: location={}, migrationsExecuted={}, success={}",
            location, result.migrationsExecuted, result.success);
    }
}
