package com.koostack.operio.bundle.core;

import com.koostack.operio.core.Operio;
import com.koostack.operio.core.spi.PluginBundle;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig;
import com.koostack.operio.plugin.consent.redis.RedisConsentDecisionService;
import com.koostack.operio.plugin.consent.redis.RedisConsentDecisionServiceConfig;
import com.koostack.operio.plugin.consent.sync.ConsentSyncServiceConfig;
import com.koostack.operio.plugin.consent.sync.ConsentSyncServiceImpl;
import com.koostack.operio.plugin.discovery.sensitive.annotation.AnnotationDiscoveryConfig;
import com.koostack.operio.plugin.redis.common.JedisProvider;

/**
 * Production server-side bundle for the Operio cloud consent stack.
 *
 * <p>This is the backbone of the Operio cloud REST API. It wires jOOQ
 * (Postgres) for the admin schema, Redis for sub-ms consent evaluation,
 * and a dual-write sync service that keeps both in lockstep.
 *
 * <p>Wires:
 * <ul>
 *   <li>{@code ConsentSyncService} ("consent-sync") — dual-write coordinator
 *       that writes to the relational DB and syncs to Redis</li>
 *   <li>{@code ConsentDecisionService} ("redis") — Redis-backed consent
 *       evaluation (single-MGET read path)</li>
 *   <li>{@code ConsentAdminService} ("jooq") — standalone admin provider
 *       exposed for schema registration (discovery pipeline)</li>
 *   <li>{@code SensitiveDiscovery} ("sensitive-annotation-discovery") —
 *       annotation-based field/category discovery (optional — only wired
 *       when an {@link AnnotationDiscoveryConfig} is provided)</li>
 * </ul>
 *
 * <p>When an {@code appName} is supplied it is set on the builder via
 * {@link Operio.Builder#withAppName(String)} so that all Redis keys are
 * namespaced under that application name, preventing collisions when
 * multiple Operio instances share the same Redis server.
 *
 * <p>The sync service internally creates its own {@code ConsentAdminService}
 * (jOOQ) from the provided config for dual-write operations. A separate
 * standalone admin provider is also registered so that
 * {@link com.koostack.operio.core.Operio#syncDiscoveredFields()} can use
 * it for schema registration (registering fields, categories, and
 * field-category links discovered by the {@code SensitiveDiscovery} plugin).
 *
 * <h2>Per-Tenant Redis ACL Isolation</h2>
 * <p>In multi-tenant deployments, pass a {@link JedisProvider} (typically a
 * {@link com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider})
 * via the {@link #CoreConsentBundle(String, JooqConsentAdminServiceConfig,
 * JedisProvider)} or {@link #CoreConsentBundle(String,
 * JooqConsentAdminServiceConfig, JedisProvider, AnnotationDiscoveryConfig)}
 * constructors. This ensures that every Redis operation — consent writes
 * (sync) and reads (evaluation) — authenticates as the current tenant's
 * dedicated Redis ACL user, providing <strong>server-side</strong> key
 * isolation enforced by Redis itself (not just application-level key-prefix
 * isolation). Each tenant's ACL user is restricted to keys matching
 * {@code {appName}:{tenantId}:*}, so even a bug in key-prefix logic
 * cannot leak data between tenants.
 *
 * <h2>Bundle Taxonomy</h2>
 * <p>This is a <strong>server-side</strong> bundle — it implements the core
 * SPIs with real infrastructure (Postgres, Redis). It runs inside the Operio
 * cloud deployment. The counterpart for development/testing is
 * {@code EmbeddedConsentBundle} (H2 + Caffeine, zero external infra). The
 * future <strong>client-side</strong> {@code CloudConsentBundle} will
 * implement the same SPIs by delegating to the Operio cloud REST API,
 * running inside the customer's JVM.
 *
 * <h2>Usage — without discovery</h2>
 * <pre>{@code
 * var jooqConfig = new JooqConsentAdminServiceConfig(
 *     "jdbc:postgresql://localhost:5432/operio", "POSTGRES"
 * );
 * var redisConfig = new RedisConsentDecisionServiceConfig("localhost", 6379);
 *
 * Operio operio = Operio.builder()
 *     .withBundle(new CoreConsentBundle(jooqConfig, redisConfig))
 *     .build();
 * }</pre>
 *
 * <h2>Usage — with annotation discovery and app name</h2>
 * <pre>{@code
 * var jooqConfig = new JooqConsentAdminServiceConfig(
 *     "jdbc:postgresql://localhost:5432/operio", "POSTGRES"
 * );
 * var redisConfig = new RedisConsentDecisionServiceConfig("localhost", 6379);
 * var discoveryConfig = AnnotationDiscoveryConfig.of(
 *     User.class, Patient.class, Order.class
 * );
 *
 * Operio operio = Operio.builder()
 *     .withBundle(new CoreConsentBundle(
 *         "my-healthcare-app", jooqConfig, redisConfig, discoveryConfig))
 *     .build();
 *
 * // Scan classes for @Sensitive, register fields/categories in the DB
 * operio.syncDiscoveredFields();
 *
 * // Now consent writes and reads work against discovered metadata
 * operio.grantConsent("user-123",
 *     ConsentScope.to("PII", "email", "User", "marketing"),
 *     Instant.now().plus(Duration.ofDays(365)));
 * }</pre>
 */
public class CoreConsentBundle implements PluginBundle {

    private final String appName;
    private final JooqConsentAdminServiceConfig jooqConfig;
    private final RedisConsentDecisionServiceConfig redisConfig;
    private final JedisProvider jedisProvider;
    private final AnnotationDiscoveryConfig discoveryConfig;

    /**
     * Creates a core bundle without annotation discovery and with
     * the default application name.
     *
     * <p>Only the consent sync (dual-write), standalone admin, and Redis
     * evaluation plugins are wired. Field and category metadata must be
     * registered manually or via a separate discovery mechanism.
     *
     * @param jooqConfig  configuration for the jOOQ-backed admin service
     * @param redisConfig configuration for the Redis-backed decision service
     */
    public CoreConsentBundle(
        JooqConsentAdminServiceConfig jooqConfig,
        RedisConsentDecisionServiceConfig redisConfig
    ) {
        this(null, jooqConfig, redisConfig, (JedisProvider) null, null);
    }

    /**
     * Creates a core bundle with optional annotation discovery but
     * no explicit application name (defaults to {@code "operio"}).
     *
     * @param jooqConfig      configuration for the jOOQ-backed admin service
     * @param redisConfig     configuration for the Redis-backed decision service
     * @param discoveryConfig configuration for annotation discovery, or
     *                        {@code null} to skip discovery wiring
     */
    public CoreConsentBundle(
        JooqConsentAdminServiceConfig jooqConfig,
        RedisConsentDecisionServiceConfig redisConfig,
        AnnotationDiscoveryConfig discoveryConfig
    ) {
        this(
            null,
            jooqConfig,
            redisConfig,
            (JedisProvider) null,
            discoveryConfig
        );
    }

    /**
     * Creates a core bundle with an explicit application name and
     * without annotation discovery.
     *
     * @param appName     the application name used to namespace Redis keys,
     *                    or {@code null} to use the default
     * @param jooqConfig  configuration for the jOOQ-backed admin service
     * @param redisConfig configuration for the Redis-backed decision service
     */
    public CoreConsentBundle(
        String appName,
        JooqConsentAdminServiceConfig jooqConfig,
        RedisConsentDecisionServiceConfig redisConfig
    ) {
        this(appName, jooqConfig, redisConfig, (JedisProvider) null, null);
    }

    /**
     * Creates a core bundle with an explicit application name and
     * optional annotation discovery.
     *
     * <p>When {@code appName} is non-null it is set on the
     * {@link Operio.Builder} so that all Redis keys are namespaced under
     * the given name. This prevents collisions when multiple Operio
     * instances share the same Redis server.
     *
     * <p>When {@code discoveryConfig} is non-null, the
     * {@code SensitiveAnnotationDiscovery} plugin is wired so that
     * {@link com.koostack.operio.core.Operio#syncDiscoveredFields()} can
     * scan the specified classes for {@code @Sensitive} annotations and
     * automatically register the discovered fields and categories into the
     * consent admin schema.
     *
     * @param appName         the application name used to namespace Redis keys,
     *                        or {@code null} to use the default
     * @param jooqConfig      configuration for the jOOQ-backed admin service
     * @param redisConfig     configuration for the Redis-backed decision service
     * @param discoveryConfig configuration for annotation discovery, or
     *                        {@code null} to skip discovery wiring
     */
    public CoreConsentBundle(
        String appName,
        JooqConsentAdminServiceConfig jooqConfig,
        RedisConsentDecisionServiceConfig redisConfig,
        AnnotationDiscoveryConfig discoveryConfig
    ) {
        this(
            appName,
            jooqConfig,
            redisConfig,
            (JedisProvider) null,
            discoveryConfig
        );
    }

    /**
     * Creates a core bundle with per-tenant Redis ACL isolation and
     * without annotation discovery.
     *
     * <p>The provided {@link JedisProvider} (typically a
     * {@link com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider})
     * is shared between the consent sync (dual-write) and consent decision
     * (evaluation) services. Each tenant's Redis operations authenticate as
     * that tenant's dedicated ACL user, which is restricted to keys matching
     * {@code {appName}:{tenantId}:*} — providing server-side key isolation
     * enforced by Redis itself.
     *
     * @param appName       the application name used to namespace Redis keys,
     *                      or {@code null} to use the default
     * @param jooqConfig    configuration for the jOOQ-backed admin service
     * @param jedisProvider the per-tenant Jedis provider for ACL-isolated
     *                      Redis connections
     */
    public CoreConsentBundle(
        String appName,
        JooqConsentAdminServiceConfig jooqConfig,
        JedisProvider jedisProvider
    ) {
        this(appName, jooqConfig, null, jedisProvider, null);
    }

    /**
     * Creates a core bundle with per-tenant Redis ACL isolation and
     * optional annotation discovery.
     *
     * @param appName         the application name used to namespace Redis keys,
     *                        or {@code null} to use the default
     * @param jooqConfig      configuration for the jOOQ-backed admin service
     * @param jedisProvider   the per-tenant Jedis provider for ACL-isolated
     *                        Redis connections
     * @param discoveryConfig configuration for annotation discovery, or
     *                        {@code null} to skip discovery wiring
     */
    public CoreConsentBundle(
        String appName,
        JooqConsentAdminServiceConfig jooqConfig,
        JedisProvider jedisProvider,
        AnnotationDiscoveryConfig discoveryConfig
    ) {
        this(appName, jooqConfig, null, jedisProvider, discoveryConfig);
    }

    /**
     * Internal all-args constructor.
     *
     * <p>When {@code jedisProvider} is non-null, it takes precedence over
     * {@code redisConfig} for Redis connection setup — ensuring per-tenant
     * ACL isolation. When {@code jedisProvider} is null, the bundle falls
     * back to creating a shared {@link redis.clients.jedis.JedisPooled}
     * from {@code redisConfig} (legacy single-tenant behavior).
     */
    private CoreConsentBundle(
        String appName,
        JooqConsentAdminServiceConfig jooqConfig,
        RedisConsentDecisionServiceConfig redisConfig,
        JedisProvider jedisProvider,
        AnnotationDiscoveryConfig discoveryConfig
    ) {
        this.appName = appName;
        this.jooqConfig = jooqConfig;
        this.redisConfig = redisConfig;
        this.jedisProvider = jedisProvider;
        this.discoveryConfig = discoveryConfig;
    }

    @Override
    public String name() {
        return "core";
    }

    @Override
    public void apply(Operio.Builder builder) {
        // Application-level namespace for Redis keys
        if (appName != null) {
            builder.withAppName(appName);
        }

        if (jedisProvider != null) {
            // --- Per-tenant ACL isolation path --------------------------------
            // When a JedisProvider is supplied (typically a
            // TenantAwareJedisProvider), use it for both the sync service
            // (write path) and the decision service (read path). This ensures
            // every Redis operation authenticates as the current tenant's
            // ACL user, which Redis restricts to that tenant's key pattern.

            // Dual-write: DB + Redis sync — using per-tenant Jedis provider
            ConsentSyncServiceImpl syncService = new ConsentSyncServiceImpl();
            syncService.configure(jooqConfig, jedisProvider, true);
            builder.withConsentSyncProvider(syncService);

            // Read-path: Redis evaluation — using the same per-tenant provider
            RedisConsentDecisionService decisionService =
                new RedisConsentDecisionService();
            decisionService.configure(jedisProvider);
            builder.withConsentProvider(decisionService);
        } else {
            // --- Legacy shared-connection path --------------------------------
            // No JedisProvider supplied — fall back to creating a shared
            // JedisPooled from the RedisConsentDecisionServiceConfig.
            // This is the backward-compatible path for single-tenant
            // deployments, embedded mode, and tests.

            // Dual-write: DB + Redis sync
            builder.withConsentSyncProvider(
                "consent-sync",
                new ConsentSyncServiceConfig(jooqConfig, redisConfig)
            );

            // Read-path: Redis evaluation (single-MGET)
            builder.withConsentProvider("redis", redisConfig);
        }

        // Standalone admin provider for schema registration (discovery
        // pipeline). This is a separate instance from the one embedded in
        // the sync service, sharing the same DB config so both point at
        // the same relational schema.
        builder.withConsentAdminProvider("jooq", jooqConfig);

        // Optional: annotation-based discovery
        if (discoveryConfig != null) {
            builder.withSensitiveDiscovery(
                "sensitive-annotation-discovery",
                discoveryConfig
            );
        }
    }
}
