package com.koostack.operio.rest.config;

import com.koostack.operio.bundle.core.CoreConsentBundle;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.service.PlatformAdminService;
import com.koostack.operio.core.platform.iam.TokenValidationService;
import com.koostack.operio.core.spi.PluginBundle;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig;
import com.koostack.operio.plugin.consent.redis.ConsentPolicyRedisRepository;
import com.koostack.operio.plugin.consent.redis.RedisConsentDecisionServiceConfig;
import com.koostack.operio.plugin.platform.jooq.JooqPlatformAdminService;
import com.koostack.operio.plugin.platform.jooq.PlatformJooqConfig;
import com.koostack.operio.plugin.platform.jooq.TenantRepository;
import com.koostack.operio.plugin.redis.common.JedisProvider;
import com.koostack.operio.plugin.redis.common.RedisClientFactory;
import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider;
import com.koostack.operio.plugin.redis.common.pubsub.RedisInvalidationPublisher;
import com.koostack.operio.tenant.consent.projection.decision.ConsentDecisionProjectionHandler;
import com.koostack.operio.tenant.event.jooq.ShardedStreamConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

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
 *   <li>{@code JooqPlatformAdminService} — tenant lifecycle, API key
 *       management, IdP configuration (wired via SPI with
 *       {@link PlatformJooqConfig})</li>
 *   <li>{@code ApiKeyTokenVerifier} — SHA-256 bearer token verification
 *       against the platform {@code api_key} table (wired via SPI with
 *       {@link PlatformJooqConfig})</li>
 * </ul>
 *
 * <h2>Embedded mode ({@code operio.mode=embedded})</h2>
 * <ul>
 *   <li>{@link EmbeddedConsentBundle} — H2 in-memory + Caffeine cache,
 *       zero external infrastructure (ideal for local dev and testing)</li>
 *   <li>Platform admin and token verifier are <strong>not</strong> wired —
 *       embedded mode has no multi-tenant management</li>
 * </ul>
 *
 * <h2>Produced beans</h2>
 * <ul>
 *   <li>{@link Operio} — the primary entry point for consent operations</li>
 *   <li>{@link PlatformAdminService} — tenant/API-key management
 *       (production only; {@code null} in embedded mode)</li>
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

    @Inject
    OperioConfig config;

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
     * @return the fully-wired {@link Operio} instance
     * @throws IllegalStateException if required configuration is missing for
     *                               the selected mode
     */
    @Produces
    @ApplicationScoped
    public Operio operio() {
        String mode = config.mode();
        LOG.info("Operio REST API: initializing (mode={})", mode);

        Operio.Builder builder = Operio.builder();

        // -- Consent bundle ---------------------------------------------------
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

        // -- Platform admin + token verifier (production only) ----------------
        if (MODE_PRODUCTION.equalsIgnoreCase(mode)) {
            PlatformJooqConfig platformConfig = createPlatformConfig();
            builder.withPlatformAdmin("jooq-platform", platformConfig);
            builder.withTokenValidationService("api-key", platformConfig);
            LOG.info(
                "Operio REST API: platform admin and token verifier wired (jooq-platform / api-key)"
            );
        } else {
            LOG.info(
                "Operio REST API: embedded mode — platform admin and token verifier not wired"
            );
        }

        Operio operio = builder.build();

        LOG.info(
            "Operio REST API: initialized successfully (appName={})",
            operio.appName()
        );
        return operio;
    }

    // -----------------------------------------------------------------------
    // Sub-service producers
    // -----------------------------------------------------------------------

    /**
     * Produces the {@link PlatformAdminService} singleton — exposes tenant
     * lifecycle, API key management, and IdP configuration.
     *
     * <p>In production mode this returns the {@code JooqPlatformAdminService}
     * wired during {@link #operio()} construction. In embedded mode it
     * returns {@code null} (no platform admin in embedded deployments).
     *
     * <p>Downstream consumers (e.g. {@code PlatformResource}) should
     * null-check or use {@code @Inject Instance<PlatformAdminService>} for
     * optional injection when running in embedded mode.
     *
     * @param operio the built {@link Operio} instance (injected by CDI)
     * @return the platform admin service, or {@code null} in embedded mode
     */
    @Produces
    @ApplicationScoped
    @SuppressWarnings("rawtypes")
    public PlatformAdminService platformAdminService(Operio operio) {
        PlatformAdminService service = operio.platformAdminService();
        if (service != null) {
            LOG.debug("Operio REST API: PlatformAdminService available");
        } else {
            LOG.debug(
                "Operio REST API: PlatformAdminService not available (embedded mode)"
            );
        }
        return service;
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
     * @param operio the built {@link Operio} instance (injected by CDI)
     * @return the token validation service, or {@code null} in embedded mode
     */
    @Produces
    @ApplicationScoped
    @SuppressWarnings("rawtypes")
    public TokenValidationService tokenValidationService(Operio operio) {
        TokenValidationService service = operio.tokenValidationService();
        if (service != null) {
            LOG.debug("Operio REST API: TokenValidationService available");
        } else {
            LOG.debug(
                "Operio REST API: TokenValidationService not available (embedded mode)"
            );
        }
        return service;
    }

    // -----------------------------------------------------------------------
    // Decision cache projection (production only)
    // -----------------------------------------------------------------------

    private ShardedStreamConsumer decisionProjectionConsumer;

    /**
     * Produces the {@link ShardedStreamConsumer} that runs the consent
     * decision projection — materializing domain events into the Redis
     * decision cache.
     *
     * <p>Only activated in production mode (when {@code adminRedisPool} and
     * {@code tenantAwareJedisProvider} are available). In embedded mode this
     * returns {@code null} (no stream consumer needed).
     *
     * <p>The consumer is started eagerly at construction. Shutdown is handled
     * by the {@link #onShutdown} CDI lifecycle callback.
     *
     * @param operio the built {@link Operio} instance (triggers bundle initialization first)
     * @return the started stream consumer, or {@code null} in embedded mode
     */
    @Produces
    @ApplicationScoped
    public ShardedStreamConsumer consentDecisionProjectionConsumer(Operio operio) {
        if (adminRedisPool == null || tenantAwareJedisProvider == null) {
            LOG.info("Operio REST API: decision projection consumer not wired (embedded mode)");
            return null;
        }

        ConsentPolicyRedisRepository consentRepo =
            new ConsentPolicyRedisRepository(tenantAwareJedisProvider);

        RedisInvalidationPublisher invalidationPublisher =
            new RedisInvalidationPublisher(adminRedisPool, new ObjectMapper().registerModule(new JavaTimeModule()));

        ConsentDecisionProjectionHandler handler =
            new ConsentDecisionProjectionHandler(consentRepo, invalidationPublisher);

        String consumerName = System.getenv().getOrDefault("HOSTNAME", "operio-api-" + ProcessHandle.current().pid());
        this.decisionProjectionConsumer = new ShardedStreamConsumer(
            JedisProvider.shared(adminRedisPool),
            handler,
            "decision-projection",
            consumerName
        );
        decisionProjectionConsumer.start();

        LOG.info("Operio REST API: decision projection consumer started (group=decision-projection, consumer={})",
            consumerName);
        return decisionProjectionConsumer;
    }

    /**
     * CDI shutdown hook — stops the stream consumer gracefully.
     */
    public void onShutdown(@jakarta.enterprise.event.Observes
                           @jakarta.enterprise.context.BeforeDestroyed(ApplicationScoped.class)
                           Object event) {
        if (decisionProjectionConsumer != null && decisionProjectionConsumer.isRunning()) {
            LOG.info("Operio REST API: stopping decision projection consumer");
            decisionProjectionConsumer.stop();
        }
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
        //
        // NOTE: The TenantRepository used here is created from the platform
        // config (same DB as the PlatformJooqConfig). It is created
        // eagerly so the credential lookup closure captures it. The
        // platform admin service creates its own TenantRepository instance
        // independently — they share the same database but have separate
        // connection pools, which is fine for read-only credential lookups.
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
     * credential lookup queries the platform {@code tenant} table for the
     * {@code redis_acl_user} and {@code redis_acl_password} columns.
     *
     * <p>This method creates a lightweight read-only
     * {@link TenantRepository} for the credential lookup. It shares the
     * same platform database as the {@link JooqPlatformAdminService} but
     * uses its own connection pool — acceptable for the low-frequency,
     * once-per-tenant credential lookups.
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
        // Create a lightweight TenantRepository for credential lookups.
        // This uses the platform DB config — same DB as PlatformJooqConfig.
        OperioConfig.DbConfig platformDb = config
            .platformDb()
            .orElseThrow(() ->
                new IllegalStateException(
                    "operio.mode=production requires operio.platform-db " +
                        "to be configured (needed for tenant Redis ACL " +
                        "credential lookup)"
                )
            );

        // -- Validate credentials before creating the DataSource ----------
        requireNonBlank(platformDb.url(), "operio.platform-db.url");
        requireNonBlank(
            platformDb.username().orElse(null),
            "operio.platform-db.username (env: OPERIO_PLATFORM_DB_USERNAME)"
        );
        requireNonBlank(
            platformDb.password().orElse(null),
            "operio.platform-db.password (env: OPERIO_PLATFORM_DB_PASSWORD)"
        );

        // Reuse JooqDataSourceFactory to create a DataSource for the
        // platform DB. We create a DSLContext from it for the
        // TenantRepository. This is a separate pool from the one the
        // PlatformAdminService uses — kept intentionally lightweight
        // since credential lookups are infrequent (once per tenant).
        var hikariConfig = new com.zaxxer.hikari.HikariConfig();
        String platformDbUrlWithSsl = appendSslParameters(
            platformDb.url(),
            platformDb
        );
        hikariConfig.setJdbcUrl(platformDbUrlWithSsl);
        platformDb.username().ifPresent(hikariConfig::setUsername);
        platformDb.password().ifPresent(hikariConfig::setPassword);
        hikariConfig.setPoolName("operio-redis-acl-lookup");
        hikariConfig.setMaximumPoolSize(2); // Only used for credential lookups
        var dataSource = new com.zaxxer.hikari.HikariDataSource(hikariConfig);

        org.jooq.SQLDialect sqlDialect = org.jooq.SQLDialect.valueOf(
            platformDb.dialect().toUpperCase()
        );
        var settings = new org.jooq.conf.Settings().withRenderNameCase(
            org.jooq.conf.RenderNameCase.LOWER
        );
        var dsl = org.jooq.impl.DSL.using(dataSource, sqlDialect, settings);
        // Pass the encryption key so that findById() transparently decrypts
        // the redis_acl_password column (encrypted at rest via AES-256-GCM).
        String encryptionKey = platformDb
            .encryptionKey()
            .filter(k -> !k.isBlank())
            .orElse(null);
        var tenantRepo = new TenantRepository(dsl, encryptionKey);

        TenantAwareJedisProvider.TenantCredentialLookup lookup = tenantId -> {
            var tenantOpt = tenantRepo.findById(tenantId);
            if (tenantOpt.isEmpty()) {
                LOG.warn(
                    "Tenant '{}' not found in platform DB — cannot " +
                        "resolve Redis ACL credentials",
                    tenantId
                );
                return null;
            }
            var tenant = tenantOpt.get();
            String aclUser = tenant.getRedisAclUser();
            String aclPassword = tenant.getRedisAclPassword();
            if (
                aclUser == null ||
                aclUser.isBlank() ||
                aclPassword == null ||
                aclPassword.isBlank()
            ) {
                LOG.warn(
                    "Tenant '{}' has no Redis ACL credentials " +
                        "(redis_acl_user='{}', redis_acl_password={}). " +
                        "This tenant was likely provisioned before per-tenant " +
                        "Redis ACL isolation was enabled. Run " +
                        "TenantManager.reprovisionRedisAcl() to generate " +
                        "credentials for this tenant.",
                    tenantId,
                    aclUser,
                    aclPassword != null ? "<set but blank>" : "<null>"
                );
                return null;
            }
            return new TenantAwareJedisProvider.TenantRedisCredentials(
                aclUser,
                aclPassword
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
            lookup
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

    /**
     * Creates a {@link PlatformJooqConfig} from the
     * {@code operio.platform-db} and {@code operio.redis} configuration.
     *
     * <p>The platform config is used to wire both
     * {@code JooqPlatformAdminService} (tenant/API-key management) and
     * {@code ApiKeyTokenVerifier} (bearer token verification). Both share
     * the same platform database and optionally the same Redis connection
     * for tenant-scoped ACL provisioning.
     *
     * @return the platform configuration
     * @throws IllegalStateException if {@code operio.platform-db} is not
     *                               configured
     */
    private PlatformJooqConfig createPlatformConfig() {
        OperioConfig.DbConfig platformDb = config
            .platformDb()
            .orElseThrow(() ->
                new IllegalStateException(
                    "operio.mode=production requires operio.platform-db to be configured"
                )
            );

        // -- Validate credentials are present and non-blank -------------------
        requireNonBlank(platformDb.url(), "operio.platform-db.url");
        requireNonBlank(
            platformDb.username().orElse(null),
            "operio.platform-db.username (env: OPERIO_PLATFORM_DB_USERNAME)"
        );
        requireNonBlank(
            platformDb.password().orElse(null),
            "operio.platform-db.password (env: OPERIO_PLATFORM_DB_PASSWORD)"
        );

        // Append SSL parameters to platform DB JDBC URL if needed
        String platformDbUrl = appendSslParameters(
            platformDb.url(),
            platformDb
        );

        // ...existing code...
        // connection from operio.redis. If Redis is not configured, platform
        // admin still works but skips Redis ACL provisioning during
        // createTenant().
        RedisPluginConfig redisPluginConfig = config
            .redis()
            .map(redis ->
                (RedisPluginConfig) new RedisConsentDecisionServiceConfig(
                    redis.host(),
                    redis.port(),
                    redis
                        .password()
                        .filter(pw -> !pw.isBlank())
                        .orElse(null)
                )
            )
            .orElse(null);

        LOG.info(
            "Operio REST API: configuring PlatformJooqConfig " +
                "(platformDb={}, redis={})",
            platformDb.url(),
            redisPluginConfig != null
                ? redisPluginConfig.host() + ":" + redisPluginConfig.port()
                : "disabled"
        );

        // -- Encryption key for redis_acl_password at rest --------------------
        String encryptionKey = platformDb
            .encryptionKey()
            .filter(k -> !k.isBlank())
            .orElse(null);
        if (encryptionKey == null) {
            LOG.warn(
                "Operio REST API: OPERIO_DB_ENCRYPTION_KEY is not set — " +
                    "redis_acl_password will be stored in PLAINTEXT. " +
                    "This is a compliance risk under HIPAA §164.312(a)(2)(iv) " +
                    "and GDPR Article 32. Set the OPERIO_DB_ENCRYPTION_KEY " +
                    "environment variable before deploying to production."
            );
        } else {
            LOG.info(
                "Operio REST API: redis_acl_password encryption at rest ENABLED"
            );
        }

        return new PlatformJooqConfig(
            platformDbUrl,
            platformDb.dialect(),
            platformDb.username().orElse(null),
            platformDb.password().orElse(null),
            redisPluginConfig,
            encryptionKey
        );
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
}
