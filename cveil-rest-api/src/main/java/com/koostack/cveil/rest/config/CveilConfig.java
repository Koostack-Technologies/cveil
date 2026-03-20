package com.koostack.cveil.rest.config;

import com.koostack.cveil.core.models.TenantContext;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.Map;
import java.util.Optional;

/**
 * SmallRye Config mapping for the {@code cveil.*} configuration namespace.
 *
 * <p>This interface is the Quarkus equivalent of the Spring Boot {@code CveilProperties} class in
 * the starter module. Quarkus and SmallRye Config use {@link ConfigMapping} interfaces (no mutable
 * POJOs) to bind configuration from {@code application.yml}, environment variables, and system
 * properties.
 *
 * <h2>Configuration Surface</h2>
 *
 * <pre>{@code
 * cveil:
 *   mode: production          # "production" or "embedded"
 *   app-name: cveil          # optional application name for Redis key namespacing
 *   db:
 *     url: jdbc:postgresql://localhost:5432/cveil
 *     dialect: POSTGRES
 *   redis:
 *     host: localhost
 *     port: 6379
 *     password:                # optional
 *   super-admin:
 *     bootstrap-key:           # optional — env var CVEIL_BOOTSTRAP_KEY
 * }</pre>
 *
 * <h2>Mode Semantics</h2>
 *
 * <ul>
 *   <li>{@code production} — wires {@code CoreConsentBundle} (Postgres + Redis) and platform CQRS
 *       services / {@code ApiKeyTokenVerifier} for multi-tenant cloud deployment.
 * </ul>
 *
 * <h2>CDI Injection Guidelines</h2>
 *
 * <p>This interface is a SmallRye {@link ConfigMapping} bean, <strong>not</strong> a normal CDI
 * {@code @ApplicationScoped} bean. SmallRye registers the config-mapping implementation during the
 * Quarkus <em>runtime init</em> phase. This has an important consequence for JAX-RS
 * {@code @Provider} classes (filters, interceptors, exception mappers):
 *
 * <blockquote>
 *
 * <strong>Never inject {@code CveilConfig} directly into a {@code @Provider} class.</strong> Use
 * {@code @Inject Instance<CveilConfig>} (lazy) instead.
 *
 * </blockquote>
 *
 * <p>JAX-RS providers are eagerly instantiated by RESTEasy Reactive during the Quarkus <em>static
 * init</em> deployment phase — before the runtime {@link io.smallrye.config.SmallRyeConfig} (which
 * holds the {@code @ConfigMapping} registrations) has been built. A direct {@code @Inject
 * CveilConfig} on a provider triggers immediate resolution, which fails with {@code SRCFG00027:
 * Could not find a mapping for ...}. Wrapping the injection in {@code Instance<CveilConfig>} defers
 * resolution to first access at request time, when the runtime config is available.
 *
 * <p>This restriction does <strong>not</strong> apply to normal CDI beans
 * ({@code @ApplicationScoped}, {@code @RequestScoped}, etc.) because Quarkus/Arc injects lazy
 * client proxies for those — the actual bean instance (and its dependencies) is only created on
 * first method invocation, which happens well after runtime init.
 *
 * <h3>Audit Results (2026-02-23)</h3>
 *
 * <p>All {@code @Provider} classes in the REST API module were audited for eager
 * {@code @ConfigMapping} injection:
 *
 * <ul>
 *   <li>{@code ApiKeyAuthFilter} — uses {@code Instance<CveilConfig>} ✓ (fixed from direct
 *       injection)
 *   <li>{@code RateLimitFilter} — does not inject any {@code @ConfigMapping} type ✓
 *   <li>{@code RequestTracingFilter} — does not inject any {@code @ConfigMapping} type ✓
 *   <li>{@code CveilExceptionMapper} — has no {@code @Inject} fields at all ✓
 * </ul>
 *
 * <p>Non-provider beans that inject {@code CveilConfig} directly ({@code CveilProducers}, {@code
 * BootstrapKeyLifecycleService}, {@code RateLimitService}) are all {@code @ApplicationScoped} and
 * receive lazy proxies — no change needed.
 *
 * @see CveilProducers
 */
@ConfigMapping(prefix = "cveil")
public interface CveilConfig {
    /**
     * The deployment mode — determines which consent bundle is wired.
     *
     * @return {@code "production"} or {@code "embedded"}
     */
    @WithDefault("production")
    String mode();

    /**
     * Optional application name used for Redis key namespacing and diagnostic logging.
     *
     * <p>When absent, defaults to {@code "cveil"} (the Cveil default).
     *
     * @return the application name, or empty to use the default
     */
    Optional<String> appName();

    /**
     * Application base URL used for constructing redirect URIs (e.g. email verification callbacks).
     *
     * <p>Defaults to {@code http://localhost:8080} for local development.
     *
     * @return the application base URL
     */
    @WithName("base-url")
    @WithDefault("http://localhost:8080")
    String baseUrl();

    /**
     * Unified database configuration (all schemas: cveilcore, cveiliam, cveilbilling, cveilevent,
     * cveilconsent, cveilmetrics).
     *
     * <p>Required when {@code mode=production}. Ignored when {@code mode=embedded} (the embedded
     * bundle uses an in-memory H2 database by default).
     *
     * @return the DB config, or empty
     */
    Optional<DbConfig> db();

    /**
     * Redis configuration for the consent decision cache.
     *
     * <p>Required when {@code mode=production}. Ignored when {@code mode=embedded} (the embedded
     * bundle uses Caffeine).
     *
     * @return the Redis config, or empty
     */
    Optional<RedisConfig> redis();

    /**
     * Super-admin bootstrap key configuration.
     *
     * <p>The bootstrap key is a one-time-use secret that allows the very first API call to create
     * the initial tenant and admin API key before any regular keys exist. Typically injected via
     * the {@code CVEIL_BOOTSTRAP_KEY} environment variable.
     *
     * @return the super-admin config, or empty
     */
    Optional<SuperAdminConfig> superAdmin();

    /**
     * Multi-tenant isolation configuration.
     *
     * <p>When enabled, DSAR endpoints (export and delete) require an active {@link TenantContext
     * TenantContext} and return 403 Forbidden if none is set. All queries are automatically routed
     * to the correct tenant schema via {@link
     * com.koostack.cveil.plugin.jooq.common.TenantAwareExecuteListener}.
     *
     * <p>Defaults to {@code false} for backward compatibility with single-tenant deployments.
     *
     * @return the multi-tenant config, or empty
     */
    Optional<MultiTenantConfig> multiTenant();

    /**
     * Config-based API keys for development and testing.
     *
     * <p>Maps key names to role/tenant specifications. The key name is used as the Bearer token
     * value. The value is a comma-separated list of directives:
     *
     * <ul>
     *   <li>{@code role:<name>} — grants the specified role
     *   <li>{@code tenant:<id>} — binds the key to the specified tenant
     * </ul>
     *
     * <p>Example configuration:
     *
     * <pre>{@code
     * cveil:
     *   api-keys:
     *     my-dev-key: "role:consent:read,role:consent:write,tenant:acme"
     * }</pre>
     *
     * <p>In production, use database-stored API keys created via the platform admin API instead.
     *
     * @return config-based API keys map (empty if none configured)
     */
    @WithName("api-keys")
    Map<String, String> apiKeys();

    /**
     * Keycloak identity provider configuration.
     *
     * <p>When present and {@code cveil.mode=production}, the platform initializes a {@code
     * KeycloakIdpProvider} as the default IdP. Tenants can override with their own IdP via the
     * {@code idp_config} table.
     */
    Optional<KeycloakConfig> keycloak();

    /**
     * Stripe payment processor configuration.
     *
     * <p>When present and {@code cveil.mode=production}, the platform initializes a {@code
     * StripePaymentProcessorService} for payment method management, subscriptions, invoicing, and
     * webhook processing.
     */
    Optional<StripeConfig> stripe();

    // -------------------------------------------------------------------
    // Nested configuration interfaces
    // -------------------------------------------------------------------

    /** Keycloak connection configuration. */
    interface KeycloakConfig {
        /** Keycloak base URL (e.g. {@code http://localhost:8180}). */
        Optional<String> url();

        /** Admin username for the Keycloak master realm. */
        @WithName("admin-username")
        @WithDefault("admin")
        String adminUsername();

        /** Admin password for the Keycloak master realm. */
        @WithName("admin-password")
        Optional<String> adminPassword();

        /** Client ID used by Cveil in each tenant realm. */
        @WithName("client-id")
        @WithDefault("cveil-backend")
        String clientId();

        /** Optional global client secret (if all realms share one). */
        @WithName("client-secret")
        Optional<String> clientSecret();

        /** Root admin email for the cveil realm (used during bootstrap). */
        @WithName("root-email")
        Optional<String> rootEmail();

        /** Root admin password for the cveil realm (used during bootstrap). */
        @WithName("root-password")
        Optional<String> rootPassword();

        /** SMTP server host for sending emails (e.g. smtp.gmail.com). */
        @WithName("smtp-host")
        Optional<String> smtpHost();

        /** SMTP server port (default 587 for STARTTLS). */
        @WithName("smtp-port")
        @WithDefault("587")
        String smtpPort();

        /** SMTP sender/from email address. */
        @WithName("smtp-from")
        Optional<String> smtpFrom();

        /** SMTP authentication username. */
        @WithName("smtp-user")
        Optional<String> smtpUser();

        /** SMTP authentication password (use app password for Gmail). */
        @WithName("smtp-password")
        Optional<String> smtpPassword();
    }

    /** Database configuration — JDBC URL and jOOQ SQL dialect. */
    interface DbConfig {
        /**
         * JDBC connection URL.
         *
         * @return the JDBC URL (e.g. {@code "jdbc:postgresql://localhost:5432/cveil"})
         */
        String url();

        /**
         * jOOQ {@link org.jooq.SQLDialect} name.
         *
         * @return the dialect string (e.g. {@code "POSTGRES"}, {@code "H2"})
         */
        @WithDefault("POSTGRES")
        String dialect();

        /**
         * Optional database username.
         *
         * <p>When present, the username is set explicitly on the HikariCP connection pool — the
         * recommended approach for production deployments because it keeps credentials out of the
         * JDBC URL (which tends to appear in logs and diagnostics).
         *
         * <p>When absent, the JDBC driver will use whatever credentials are embedded in the URL
         * (e.g. {@code ?user=foo}) or its own defaults.
         *
         * @return the database username, or empty to use the driver default
         */
        Optional<String> username();

        /**
         * Optional database password.
         *
         * <p>When present, the password is set explicitly on the HikariCP connection pool. When
         * absent, the JDBC driver falls back to whatever is encoded in the URL or its own default.
         *
         * @return the database password, or empty to use the driver default
         */
        Optional<String> password();

        /**
         * Optional encryption key for encrypting sensitive columns at rest (e.g. {@code
         * redis_acl_password} in the {@code tenant} table).
         *
         * <p>Uses AES-256-GCM — the key string is hashed with SHA-256 to produce a 256-bit
         * encryption key. Any non-empty string is valid.
         *
         * <p><strong>Required in production.</strong> When absent, passwords are stored in
         * plaintext (backward-compatible but insecure — a startup warning is logged). Typically
         * supplied via the {@code CVEIL_DB_ENCRYPTION_KEY} environment variable.
         *
         * @return the encryption key, or empty to disable encryption
         */
        Optional<String> encryptionKey();

        /**
         * PostgreSQL SSL mode for the JDBC connection.
         *
         * <p>Determines how the JDBC connection enforces TLS encryption and certificate
         * verification. Valid values:
         *
         * <ul>
         *   <li>{@code disable} — no SSL/TLS (plaintext TCP)
         *   <li>{@code require} — TLS required, but no certificate verification (vulnerable to MITM
         *       but encrypts in transit)
         *   <li>{@code verify-ca} — TLS required, verify server certificate against the CA cert
         *       specified in {@link #sslCertPath()}
         *   <li>{@code verify-full} — TLS required, verify server certificate and verify server
         *       hostname matches certificate CN/SAN
         * </ul>
         *
         * <p><strong>For production deployments:</strong> Use {@code verify-full} or {@code
         * verify-ca} with proper certificate management. Defaults to {@code require} for production
         * mode and {@code disable} for embedded mode to maintain backward compatibility.
         *
         * <p>This setting is appended to the JDBC URL as {@code ?sslmode={value}}.
         *
         * <p>For HIPAA §164.312(a)(2)(ii) and GDPR Article 32 compliance, TLS is strongly
         * recommended for all remote database connections.
         *
         * @return the SSL mode name (e.g., {@code "disable"}, {@code "require"}, {@code
         *     "verify-ca"}, {@code "verify-full"})
         */
        @WithDefault("require")
        String sslMode();

        /**
         * Optional path to a PEM-encoded certificate file for TLS server certificate verification.
         *
         * <p>Used when {@link #sslMode()} is {@code verify-ca} or {@code verify-full} to provide
         * the Certificate Authority (CA) certificate bundle. The certificate file should be
         * PEM-encoded (typically with a {@code .pem} extension).
         *
         * <p>When not provided with a verification mode, certificate verification is skipped
         * (equivalent to {@code require} mode).
         *
         * <p>For Kubernetes deployments, this path typically points to a mounted secret volume
         * (e.g. {@code /etc/ssl/certs/ca.pem}).
         *
         * @return the path to the SSL certificate file, or empty if not configured
         */
        Optional<String> sslCertPath();

        /**
         * Optional path to a PEM-encoded private key file for client certificate authentication.
         *
         * <p>When provided, the client will present this certificate to the PostgreSQL server for
         * mutual TLS (mTLS) authentication. Requires {@link #sslMode()} to be set to a verification
         * mode.
         *
         * <p>Most deployments will not need this — standard username/password authentication over
         * TLS (with server certificate verification) is sufficient. Client certificates are
         * typically used for high-security setups with certificate-based access control.
         *
         * @return the path to the SSL private key file, or empty if not configured
         */
        Optional<String> sslKeyPath();
    }

    /** Redis connection configuration. */
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

        /**
         * Enable or disable TLS for Redis connections.
         *
         * <p>When true, {@link JedisPooled} connections will use TLS encryption (typically on port
         * 6380 — the standard Redis TLS port). When false, connections use plaintext TCP (default
         * for backward compatibility).
         *
         * <p><strong>For production deployments:</strong> Set to {@code true} to encrypt Redis ACL
         * credentials in transit. Required for HIPAA §164.312(a)(2)(ii) and GDPR Article 32
         * compliance.
         *
         * <p>Defaults to {@code false} for backward compatibility and embedded mode, {@code true}
         * is recommended for production. A startup warning is logged if TLS is disabled in
         * production mode.
         *
         * @return {@code true} to enable TLS, {@code false} for plaintext
         */
        @WithDefault("false")
        boolean tlsEnabled();

        /**
         * Optional path to a PEM-encoded CA certificate file for Redis server certificate
         * verification.
         *
         * <p>Used when {@link #tlsEnabled()} is {@code true} to verify the Redis server's
         * certificate. When not provided, certificate verification is skipped (TLS is used but
         * subject to MITM attacks).
         *
         * <p>For Kubernetes deployments, this typically points to a mounted secret volume (e.g.
         * {@code /etc/ssl/certs/redis-ca.pem}).
         *
         * <p>For maximum security in production, always provide this file and ensure it contains
         * only trusted CA certificates.
         *
         * @return the path to the Redis CA certificate file, or empty if not configured
         */
        Optional<String> tlsCaCertPath();

        /**
         * Require peer verification when TLS is enabled.
         *
         * <p>When {@code true} and TLS is enabled, the Redis client will verify that the server
         * certificate is signed by a trusted CA and that the certificate hostname matches the
         * connection host.
         *
         * <p>When {@code false}, TLS is used but server certificate verification is skipped —
         * encrypts in transit but is vulnerable to MITM attacks.
         *
         * <p>For production deployments, this should be {@code true}. Defaults to {@code true} when
         * TLS is enabled and a CA cert path is provided.
         *
         * @return {@code true} to verify peer certificates, {@code false} to skip verification
         */
        @WithDefault("true")
        boolean tlsVerifyPeer();
    }

    /** Super-admin bootstrap configuration. */
    interface SuperAdminConfig {
        /**
         * The bootstrap key — a one-time-use secret for initial platform setup.
         *
         * <p>Typically supplied via {@code ${CVEIL_BOOTSTRAP_KEY:}} in {@code application.yml} so
         * it comes from an environment variable and is never committed to source control.
         *
         * @return the bootstrap key, or empty if not configured
         */
        Optional<String> bootstrapKey();

        /**
         * Force-enable the bootstrap key even after it has been auto-disabled.
         *
         * <p>When the bootstrap key lifecycle service detects that real API keys already exist
         * (either at startup or after the first key is created at runtime), it auto-disables the
         * bootstrap key for security. Setting this flag to {@code true} overrides the auto-disable
         * — the bootstrap key will be accepted regardless.
         *
         * <p><strong>Security warning:</strong> This flag is an escape hatch for disaster recovery
         * scenarios (e.g. all API keys were accidentally revoked). It should <em>never</em> be left
         * enabled in production. The lifecycle service logs a {@code WARN} message whenever this
         * override is active.
         *
         * <p>Typically supplied via {@code ${CVEIL_FORCE_BOOTSTRAP:false}} in {@code
         * application.yml} so it comes from an environment variable.
         *
         * @return {@code true} to force-enable the bootstrap key, {@code false} (default) to
         *     respect auto-disable
         */
        @WithDefault("false")
        boolean forceBootstrap();
    }

    /**
     * Multi-tenant isolation configuration.
     *
     * <p>Controls whether DSAR endpoints enforce tenant context validation. When {@link #enabled()}
     * is {@code true}, all DSAR operations require an active {@link TenantContext TenantContext}
     * and return 403 Forbidden if none is set.
     *
     * <h2>Configuration Example</h2>
     *
     * <pre>{@code
     * cveil:
     *   multi-tenant:
     *     enabled: true  # Set to true for multi-tenant SaaS deployments
     * }</pre>
     */
    interface MultiTenantConfig {
        /**
         * Whether multi-tenant isolation is enabled.
         *
         * <p>When {@code true}, DSAR endpoints (export and delete) validate that a tenant context
         * is active and return 403 if missing. When {@code false} (default), DSAR operations work
         * without tenant context validation (single-tenant mode).
         *
         * @return {@code true} to enforce tenant isolation, {@code false} for single-tenant mode
         */
        @WithDefault("false")
        boolean enabled();
    }

    /**
     * Redis stream sharding configuration.
     *
     * <p>Controls how tenant events are distributed across fixed shard streams. Instead of one
     * stream per tenant (O(N) connections), events are routed to {@code shard-count} fixed streams
     * using {@code hash(tenantId) % shardCount}.
     *
     * <h2>Configuration Example</h2>
     *
     * <pre>{@code
     * cveil:
     *   stream:
     *     shard-count: 8  # Number of shard streams (default 8)
     * }</pre>
     */
    interface StreamConfig {
        /**
         * Number of shard streams for tenant event distribution.
         *
         * <p>Events are routed to {@code cveil:stream:shard:{hash(tenantId) % shardCount}}. The
         * publisher and consumer must use the same value. Changing this value requires draining
         * existing shard streams.
         *
         * @return the shard count (default 8)
         */
        @WithName("shard-count")
        @WithDefault("8")
        int shardCount();
    }

    /**
     * Stream sharding configuration.
     *
     * @return the stream config, or empty to use defaults
     */
    Optional<StreamConfig> stream();

    // -------------------------------------------------------------------
    // Convenience methods
    // -------------------------------------------------------------------

    /**
     * Whether multi-tenant isolation is enabled.
     *
     * <p>Convenience method that checks if the {@link #multiTenant()} config is present and {@link
     * MultiTenantConfig#enabled()} is {@code true}.
     *
     * @return {@code true} if multi-tenant mode is active
     */
    default boolean isMultiTenantEnabled() {
        return multiTenant().map(MultiTenantConfig::enabled).orElse(false);
    }

    /**
     * Rate limit default configuration.
     *
     * <p>Provides configurable fallback values for tenants without an assigned plan. Overrides the
     * hardcoded defaults (30 burst/min, 10,000 eval/month, 500 subjects/month).
     *
     * <pre>{@code
     * cveil:
     *   rate-limit:
     *     default-burst: 30
     *     default-eval: 10000
     *     default-subject: 500
     * }</pre>
     */
    Optional<RateLimitConfig> rateLimit();

    /** Rate limit fallback defaults for tenants without a plan. */
    interface RateLimitConfig {
        /** Default burst limit (requests per minute). */
        @WithName("default-burst")
        @WithDefault("30")
        int defaultBurst();

        /** Default monthly evaluation limit. */
        @WithName("default-eval")
        @WithDefault("10000")
        long defaultEval();

        /** Default monthly subject limit. */
        @WithName("default-subject")
        @WithDefault("500")
        long defaultSubject();
    }

    /** Stripe payment processor configuration. */
    interface StripeConfig {
        /** Stripe secret API key (e.g. {@code sk_test_xxx} or {@code sk_live_xxx}). */
        @WithName("secret-key")
        Optional<String> secretKey();

        /** Stripe publishable key for frontend SDK initialization (e.g. {@code pk_test_xxx}). */
        @WithName("publishable-key")
        Optional<String> publishableKey();

        /** Stripe webhook endpoint secret for signature verification. */
        @WithName("webhook-secret")
        Optional<String> webhookSecret();
    }
}
