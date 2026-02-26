package com.koostack.operio.plugin.platform.jooq;

import com.koostack.operio.plugin.jooq.common.JooqPluginConfig;
import com.koostack.operio.plugin.redis.common.RedisPluginConfig;

/**
 * Configuration record for the {@code operio-platform-jooq} module.
 *
 * <p>Extends the standard {@link JooqPluginConfig} (JDBC URL + dialect) with
 * an optional {@link RedisPluginConfig} needed by {@link TenantManager} to
 * provision per-tenant Redis ACL users during
 * {@link JooqPlatformAdminService#createTenant}.
 *
 * <p>When {@code redis} is {@code null}, tenant provisioning skips the Redis
 * ACL step — useful for embedded / test deployments that don't use Redis.
 *
 * <h2>Credential Handling</h2>
 * <p>When {@code username} and {@code password} are non-null they are
 * forwarded to HikariCP as explicit connection credentials — the
 * recommended approach for production deployments because it keeps secrets
 * out of JDBC URL strings (which tend to appear in logs and diagnostics).
 *
 * <p>When {@code null} (the default for the three-arg constructor), HikariCP
 * falls back to whatever the JDBC driver extracts from the URL itself
 * (e.g. {@code ?user=foo&password=bar}) or its own defaults — preserving
 * full backward compatibility with existing callers.
 *
 * <h2>Example usage</h2>
 * <pre>{@code
 * // Production (Postgres + Redis, explicit credentials):
 * PlatformJooqConfig config = new PlatformJooqConfig(
 *     "jdbc:postgresql://localhost:5432/operio_platform",
 *     "POSTGRES",
 *     "operio",
 *     "s3cret",
 *     new MyRedisConfig("localhost", 6379, "secret")
 * );
 *
 * // Production (credentials in URL, legacy style):
 * PlatformJooqConfig config = new PlatformJooqConfig(
 *     "jdbc:postgresql://localhost:5432/operio_platform?user=operio&password=s3cret",
 *     "POSTGRES",
 *     new MyRedisConfig("localhost", 6379, "secret")
 * );
 *
 * // Embedded / test (H2, no Redis):
 * PlatformJooqConfig config = new PlatformJooqConfig(
 *     "jdbc:h2:mem:operio_platform_test;DB_CLOSE_DELAY=-1",
 *     "H2",
 *     null
 * );
 * }</pre>
 *
 * @param connectionString the JDBC URL for the platform database
 * @param dialect          the jOOQ {@link org.jooq.SQLDialect} name
 *                         (e.g. {@code "POSTGRES"}, {@code "H2"})
 * @param username         the database username, or {@code null} to use
 *                         the driver default
 * @param password         the database password, or {@code null} to use
 *                         the driver default
 * @param redis            Redis connection config for {@link TenantManager}
 *                         ACL provisioning, or {@code null} to skip Redis
 * @param encryptionKey    the AES-256-GCM encryption key for encrypting
 *                         sensitive columns (e.g. {@code redis_acl_password})
 *                         at rest. Sourced from the
 *                         {@code OPERIO_DB_ENCRYPTION_KEY} environment
 *                         variable. When {@code null}, encryption is
 *                         disabled (backward-compatible but insecure —
 *                         a startup warning is logged in production mode).
 */
public record PlatformJooqConfig(
    String connectionString,
    String dialect,
    String username,
    String password,
    RedisPluginConfig redis,
    String encryptionKey
) implements JooqPluginConfig {
    /**
     * Backward-compatible constructor — creates a config without explicit
     * database credentials or encryption key. The JDBC driver will use
     * whatever is encoded in the URL or its own defaults.
     *
     * @param connectionString the JDBC URL for the platform database
     * @param dialect          the jOOQ {@link org.jooq.SQLDialect} name
     * @param redis            Redis connection config, or {@code null}
     */
    public PlatformJooqConfig(
        String connectionString,
        String dialect,
        RedisPluginConfig redis
    ) {
        this(connectionString, dialect, null, null, redis, null);
    }

    /**
     * Backward-compatible constructor — creates a config with explicit
     * database credentials but no encryption key.
     *
     * @param connectionString the JDBC URL for the platform database
     * @param dialect          the jOOQ {@link org.jooq.SQLDialect} name
     * @param username         the database username, or {@code null}
     * @param password         the database password, or {@code null}
     * @param redis            Redis connection config, or {@code null}
     */
    public PlatformJooqConfig(
        String connectionString,
        String dialect,
        String username,
        String password,
        RedisPluginConfig redis
    ) {
        this(connectionString, dialect, username, password, redis, null);
    }
}
