package com.koostack.operio.rest.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * SmallRye Config mapping for the {@code operio.*} configuration namespace.
 *
 * <p>This interface is the Quarkus equivalent of the Spring Boot
 * {@code OperioProperties} class in the starter module. Quarkus and SmallRye
 * Config use {@link ConfigMapping} interfaces (no mutable POJOs) to bind
 * configuration from {@code application.yml}, environment variables, and
 * system properties.
 *
 * <h2>Configuration Surface</h2>
 * <pre>{@code
 * operio:
 *   mode: production          # "production" or "embedded"
 *   app-name: operio          # optional application name for Redis key namespacing
 *   db:
 *     url: jdbc:postgresql://localhost:5432/operio
 *     dialect: POSTGRES
 *   redis:
 *     host: localhost
 *     port: 6379
 *     password:                # optional
 *   platform-db:
 *     url: jdbc:postgresql://localhost:5432/operio
 *     dialect: POSTGRES
 *   super-admin:
 *     bootstrap-key:           # optional — env var OPERIO_BOOTSTRAP_KEY
 * }</pre>
 *
 * <h2>Mode Semantics</h2>
 * <ul>
 *   <li>{@code production} — wires {@code CoreConsentBundle} (Postgres + Redis)
 *       and {@code JooqPlatformAdminService} / {@code ApiKeyTokenVerifier} for
 *       multi-tenant cloud deployment.</li>
 *   <li>{@code embedded} — wires {@code EmbeddedConsentBundle} (H2 + Caffeine)
 *       for local development and testing. Platform admin and token verifier
 *       are not wired (no tenant management in embedded mode).</li>
 * </ul>
 *
 * @see OperioProducers
 */
@ConfigMapping(prefix = "operio")
public interface OperioConfig {
    /**
     * The deployment mode — determines which consent bundle is wired.
     *
     * @return {@code "production"} or {@code "embedded"}
     */
    @WithDefault("production")
    String mode();

    /**
     * Optional application name used for Redis key namespacing and
     * diagnostic logging.
     *
     * <p>When absent, defaults to {@code "operio"} (the Operio default).
     *
     * @return the application name, or empty to use the default
     */
    Optional<String> appName();

    /**
     * Consent database configuration (jOOQ admin + sync layer).
     *
     * <p>Required when {@code mode=production}. Ignored when
     * {@code mode=embedded} (the embedded bundle uses an in-memory H2
     * database by default).
     *
     * @return the consent DB config, or empty
     */
    Optional<DbConfig> db();

    /**
     * Redis configuration for the consent decision cache.
     *
     * <p>Required when {@code mode=production}. Ignored when
     * {@code mode=embedded} (the embedded bundle uses Caffeine).
     *
     * @return the Redis config, or empty
     */
    Optional<RedisConfig> redis();

    /**
     * Platform database configuration (tenant management, API keys, IdP).
     *
     * <p>Required when {@code mode=production}. In many deployments this
     * points to the same Postgres instance as {@link #db()} but could be a
     * separate database for isolation.
     *
     * @return the platform DB config, or empty
     */
    Optional<DbConfig> platformDb();

    /**
     * Super-admin bootstrap key configuration.
     *
     * <p>The bootstrap key is a one-time-use secret that allows the very
     * first API call to create the initial tenant and admin API key before
     * any regular keys exist. Typically injected via the
     * {@code OPERIO_BOOTSTRAP_KEY} environment variable.
     *
     * @return the super-admin config, or empty
     */
    Optional<SuperAdminConfig> superAdmin();

    // -------------------------------------------------------------------
    // Nested configuration interfaces
    // -------------------------------------------------------------------

    /**
     * Database configuration — JDBC URL and jOOQ SQL dialect.
     */
    interface DbConfig {
        /**
         * JDBC connection URL.
         *
         * @return the JDBC URL (e.g. {@code "jdbc:postgresql://localhost:5432/operio"})
         */
        String url();

        /**
         * jOOQ {@link org.jooq.SQLDialect} name.
         *
         * @return the dialect string (e.g. {@code "POSTGRES"}, {@code "H2"})
         */
        @WithDefault("POSTGRES")
        String dialect();
    }

    /**
     * Redis connection configuration.
     */
    interface RedisConfig {
        /**
         * Redis server hostname.
         *
         * @return the hostname (e.g. {@code "localhost"})
         */
        String host();

        /**
         * Redis server port.
         *
         * @return the port number (default {@code 6379})
         */
        @WithDefault("6379")
        int port();

        /**
         * Optional Redis password for authenticated connections.
         *
         * @return the password, or empty for unauthenticated connections
         */
        Optional<String> password();
    }

    /**
     * Super-admin bootstrap configuration.
     */
    interface SuperAdminConfig {
        /**
         * The bootstrap key — a one-time-use secret for initial platform
         * setup.
         *
         * <p>Typically supplied via {@code ${OPERIO_BOOTSTRAP_KEY:}} in
         * {@code application.yml} so it comes from an environment variable
         * and is never committed to source control.
         *
         * @return the bootstrap key, or empty if not configured
         */
        Optional<String> bootstrapKey();

        /**
         * Force-enable the bootstrap key even after it has been
         * auto-disabled.
         *
         * <p>When the bootstrap key lifecycle service detects that real
         * API keys already exist (either at startup or after the first
         * key is created at runtime), it auto-disables the bootstrap key
         * for security. Setting this flag to {@code true} overrides the
         * auto-disable — the bootstrap key will be accepted regardless.
         *
         * <p><strong>Security warning:</strong> This flag is an escape
         * hatch for disaster recovery scenarios (e.g. all API keys were
         * accidentally revoked). It should <em>never</em> be left enabled
         * in production. The lifecycle service logs a {@code WARN} message
         * whenever this override is active.
         *
         * <p>Typically supplied via {@code ${OPERIO_FORCE_BOOTSTRAP:false}}
         * in {@code application.yml} so it comes from an environment
         * variable.
         *
         * @return {@code true} to force-enable the bootstrap key,
         *         {@code false} (default) to respect auto-disable
         */
        @WithDefault("false")
        boolean forceBootstrap();
    }
}
