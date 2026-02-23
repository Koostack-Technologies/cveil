package com.koostack.operio.rest.config;

import com.koostack.operio.bundle.core.CoreConsentBundle;
import com.koostack.operio.bundle.embedded.EmbeddedConsentBundle;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.service.PlatformAdminService;
import com.koostack.operio.core.service.TokenVerifier;
import com.koostack.operio.core.spi.PluginBundle;
import com.koostack.operio.plugin.consent.jooq.JooqConsentAdminServiceConfig;
import com.koostack.operio.plugin.consent.redis.RedisConsentDecisionServiceConfig;
import com.koostack.operio.plugin.platform.jooq.PlatformJooqConfig;
import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>{@link TokenVerifier} — bearer token verification
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
            builder.withTokenVerifier("api-key", platformConfig);
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
    public PlatformAdminService<?> platformAdminService(Operio operio) {
        PlatformAdminService<?> service = operio.platformAdminService();
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
     * Produces the {@link TokenVerifier} singleton — used by
     * {@code ApiKeyAuthFilter} to authenticate bearer tokens on every
     * request.
     *
     * <p>In production mode this returns the {@code ApiKeyTokenVerifier}
     * wired during {@link #operio()} construction. In embedded mode it
     * returns {@code null}.
     *
     * @param operio the built {@link Operio} instance (injected by CDI)
     * @return the token verifier, or {@code null} in embedded mode
     */
    @Produces
    @ApplicationScoped
    public TokenVerifier<?> tokenVerifier(Operio operio) {
        TokenVerifier<?> verifier = operio.tokenVerifier();
        if (verifier != null) {
            LOG.debug("Operio REST API: TokenVerifier available");
        } else {
            LOG.debug(
                "Operio REST API: TokenVerifier not available (embedded mode)"
            );
        }
        return verifier;
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

        JooqConsentAdminServiceConfig jooqConfig =
            new JooqConsentAdminServiceConfig(db.url(), db.dialect());

        RedisConsentDecisionServiceConfig redisConfig = redis
            .password()
            .filter(pw -> !pw.isBlank())
            .map(pw ->
                new RedisConsentDecisionServiceConfig(
                    redis.host(),
                    redis.port(),
                    pw
                )
            )
            .orElseGet(() ->
                new RedisConsentDecisionServiceConfig(
                    redis.host(),
                    redis.port()
                )
            );

        String appName = config.appName().orElse(null);

        LOG.info(
            "Operio REST API: configuring CoreConsentBundle " +
                "(db={}, redis={}:{}, appName={})",
            db.url(),
            redis.host(),
            redis.port(),
            appName != null ? appName : "<default>"
        );

        if (appName != null && !appName.isBlank()) {
            return new CoreConsentBundle(appName, jooqConfig, redisConfig);
        }
        return new CoreConsentBundle(jooqConfig, redisConfig);
    }

    /**
     * Creates an {@link EmbeddedConsentBundle} (H2 + Caffeine) for local
     * development and testing.
     *
     * <p>Uses default H2 in-memory settings. The application name (if
     * configured) is set on the {@link Operio.Builder} after the bundle is
     * applied — see {@link #operio()} — rather than passed to the bundle
     * constructor, avoiding a compile-time dependency on
     * {@code AnnotationDiscoveryConfig}.
     *
     * @return the embedded consent bundle
     */
    private EmbeddedConsentBundle createEmbeddedConsentBundle() {
        LOG.info(
            "Operio REST API: configuring EmbeddedConsentBundle " +
                "(H2 + Caffeine, appName={})",
            config
                .appName()
                .filter(n -> !n.isBlank())
                .orElse("<default>")
        );

        return new EmbeddedConsentBundle();
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

        // Redis config for tenant ACL provisioning — reuse the same Redis
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

        return new PlatformJooqConfig(
            platformDb.url(),
            platformDb.dialect(),
            redisPluginConfig
        );
    }
}
