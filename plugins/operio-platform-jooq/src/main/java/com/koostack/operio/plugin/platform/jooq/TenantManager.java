package com.koostack.operio.plugin.platform.jooq;

import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.plugin.jooq.common.TenantAwareExecuteListener;
import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

/**
 * Tenant infrastructure lifecycle manager — provisions and tears down the
 * per-tenant Postgres schema and Redis ACL user.
 *
 * <h2>Provisioning ({@link #provisionTenant})</h2>
 * <ol>
 *   <li>Creates Postgres schema {@code t_{tenantId}} via
 *       {@code CREATE SCHEMA IF NOT EXISTS}.</li>
 *   <li>Runs consent Flyway migrations against the new schema (reuses
 *       migration files from {@code operio-consent-admin-jooq}).</li>
 *   <li>If a Redis config is provided, provisions a Redis ACL user
 *       ({@code t_{tenantId}}) restricted to the tenant's key prefix
 *       ({@code {appName}:{tenantId}:*}).</li>
 * </ol>
 *
 * <h2>Teardown ({@link #dropTenant})</h2>
 * <ol>
 *   <li>Drops the Postgres schema {@code t_{tenantId}} with {@code CASCADE}.</li>
 *   <li>If a Redis config is provided, removes the Redis ACL user.</li>
 * </ol>
 *
 * <h2>Schema Name Convention</h2>
 * <p>Tenant schemas follow the naming pattern {@code t_{tenantId}}, where
 * the tenant ID is sanitized to contain only {@code [a-zA-Z0-9_]}
 * characters. This reuses the same sanitization logic as
 * {@link TenantAwareExecuteListener}.
 *
 * <h2>Redis ACL User Convention</h2>
 * <p>The Redis ACL username is {@code t_{tenantId}} (same as the schema
 * name). The user is restricted to keys matching
 * {@code {appName}:{tenantId}:*} and granted only safe command
 * categories ({@code @read}, {@code @write}, {@code @connection}),
 * explicitly denying {@code @admin} and {@code @dangerous}.
 *
 * <h2>Redis ACL Implementation Note</h2>
 * <p>Redis ACL commands ({@code ACL SETUSER}, {@code ACL DELUSER}) are not
 * available on {@link redis.clients.jedis.JedisPooled} /
 * {@link redis.clients.jedis.UnifiedJedis} in Jedis 5.x. They are only
 * exposed on the {@link Jedis} class directly. This manager therefore
 * creates a short-lived {@link Jedis} connection for each ACL operation
 * using the host/port/password from the {@link RedisPluginConfig}.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is safe for concurrent use — each method acquires its
 * own JDBC connection from the pool and Redis operations use dedicated
 * connections. However, concurrent provisioning of the <em>same</em>
 * tenant is not guarded against (caller's responsibility via the platform
 * admin service layer).
 *
 * @see TenantAwareExecuteListener
 * @see JooqPlatformAdminService
 */
public class TenantManager {

    private static final Logger log = LoggerFactory.getLogger(
        TenantManager.class
    );

    /**
     * Pattern that matches valid tenant ID characters: alphanumeric and
     * underscore only. Reuses the same constraint as
     * {@link TenantAwareExecuteListener}.
     */
    private static final Pattern VALID_TENANT_ID = Pattern.compile(
        "^[a-zA-Z0-9_]+$"
    );

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DSLContext platformDsl;
    private final DataSource dataSource;
    private final TenantRepository tenantRepository;
    private final RedisPluginConfig redisConfig;
    private final String appName;
    private final String consentMigrationLocation;

    /**
     * Result of a successful tenant provisioning operation.
     *
     * @param schemaName       the Postgres schema created for the tenant
     * @param redisAclUser     the Redis ACL username, or {@code null} if
     *                         Redis is not configured
     * @param redisAclPassHash the password hash for the Redis ACL user, or
     *                         {@code null} if Redis is not configured
     */
    public record ProvisionResult(
        String schemaName,
        String redisAclUser,
        String redisAclPassHash
    ) {}

    /**
     * Creates a new tenant manager.
     *
     * @param platformDsl               the platform {@link DSLContext} (non-tenant-aware,
     *                                  targets the shared platform schema)
     * @param dataSource                the {@link DataSource} backing the platform database,
     *                                  used by Flyway for per-tenant schema migrations
     * @param tenantRepository          the tenant repository for querying active tenants
     *                                  during {@link #migrateAllTenants}
     * @param redisConfig               the Redis connection config for ACL provisioning,
     *                                  or {@code null} to skip Redis operations
     * @param appName                   the application name used as a Redis key prefix
     *                                  (e.g. {@code "operio"})
     * @param consentMigrationLocation  the Flyway migration location for consent schema
     *                                  migrations (e.g. {@code "classpath:db/migration/consent"})
     */
    public TenantManager(
        DSLContext platformDsl,
        DataSource dataSource,
        TenantRepository tenantRepository,
        RedisPluginConfig redisConfig,
        String appName,
        String consentMigrationLocation
    ) {
        this.platformDsl = platformDsl;
        this.dataSource = dataSource;
        this.tenantRepository = tenantRepository;
        this.redisConfig = redisConfig;
        this.appName = appName != null ? appName : "operio";
        this.consentMigrationLocation =
            consentMigrationLocation != null
                ? consentMigrationLocation
                : "classpath:db/migration/consent";
    }

    // -----------------------------------------------------------------------
    // Provisioning
    // -----------------------------------------------------------------------

    /**
     * Provisions infrastructure for a new tenant:
     * <ol>
     *   <li>Creates Postgres schema {@code t_{tenantId}}</li>
     *   <li>Runs consent Flyway migrations against the new schema</li>
     *   <li>Provisions a Redis ACL user (if Redis is configured)</li>
     * </ol>
     *
     * @param tenantId the tenant identifier (alphanumeric + underscore only)
     * @return the provisioning result containing schema name and Redis
     *         ACL credentials
     * @throws OperioServiceException   if schema creation or migration fails
     * @throws IllegalArgumentException if the tenant ID contains illegal characters
     */
    public ProvisionResult provisionTenant(String tenantId) {
        String sanitized = sanitizeTenantId(tenantId);
        String schemaName =
            TenantAwareExecuteListener.SCHEMA_PREFIX + sanitized;

        log.info("Provisioning tenant '{}': schema={}", tenantId, schemaName);

        // 1. Create Postgres schema
        try {
            platformDsl.execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
            log.debug("Created Postgres schema '{}'", schemaName);
        } catch (Exception e) {
            throw new OperioServiceException(
                "Failed to create Postgres schema '" +
                    schemaName +
                    "' for tenant '" +
                    tenantId +
                    "'",
                e
            );
        }

        // 2. Run consent Flyway migrations against the new schema
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .schemas(schemaName)
                .defaultSchema(schemaName)
                .locations(consentMigrationLocation)
                .load()
                .migrate();
            log.debug("Applied consent migrations to schema '{}'", schemaName);
        } catch (Exception e) {
            // Best effort: try to drop the schema we just created
            try {
                platformDsl.execute(
                    "DROP SCHEMA IF EXISTS " + schemaName + " CASCADE"
                );
            } catch (Exception cleanup) {
                log.warn(
                    "Failed to clean up schema '{}' after migration failure",
                    schemaName,
                    cleanup
                );
            }
            throw new OperioServiceException(
                "Failed to apply consent migrations to schema '" +
                    schemaName +
                    "' for tenant '" +
                    tenantId +
                    "'",
                e
            );
        }

        // 3. Provision Redis ACL user (if Redis is configured)
        String redisAclUser = null;
        String redisAclPassHash = null;
        if (redisConfig != null) {
            try {
                redisAclUser =
                    TenantAwareExecuteListener.SCHEMA_PREFIX + sanitized;
                String password = generateSecurePassword();
                redisAclPassHash = sha256Hex(password);

                String keyPattern = appName + ":" + tenantId + ":*";

                // ACL commands are only available on Jedis (not JedisPooled /
                // UnifiedJedis) in Jedis 5.x. Create a short-lived connection.
                try (Jedis jedis = createJedisConnection()) {
                    jedis.aclSetUser(
                        redisAclUser,
                        "on",
                        ">" + password,
                        "~" + keyPattern,
                        "+@read",
                        "+@write",
                        "+@connection",
                        "-@admin",
                        "-@dangerous"
                    );
                }

                log.debug(
                    "Provisioned Redis ACL user '{}' with key pattern '{}'",
                    redisAclUser,
                    keyPattern
                );
            } catch (Exception e) {
                log.warn(
                    "Failed to provision Redis ACL user for tenant '{}'. " +
                        "Schema was created successfully — Redis ACL can be retried.",
                    tenantId,
                    e
                );
                // Don't fail the whole provisioning — schema is already created.
                // Redis ACL is best-effort and can be retried.
            }
        }

        log.info(
            "Tenant '{}' provisioned successfully: schema={}, redisUser={}",
            tenantId,
            schemaName,
            redisAclUser
        );

        return new ProvisionResult(schemaName, redisAclUser, redisAclPassHash);
    }

    // -----------------------------------------------------------------------
    // Teardown
    // -----------------------------------------------------------------------

    /**
     * Tears down infrastructure for a tenant:
     * <ol>
     *   <li>Drops the Postgres schema {@code t_{tenantId}} with CASCADE</li>
     *   <li>Removes the Redis ACL user (if Redis is configured)</li>
     * </ol>
     *
     * @param tenantId the tenant identifier
     * @throws OperioServiceException   if schema drop fails
     * @throws IllegalArgumentException if the tenant ID contains illegal characters
     */
    public void dropTenant(String tenantId) {
        String sanitized = sanitizeTenantId(tenantId);
        String schemaName =
            TenantAwareExecuteListener.SCHEMA_PREFIX + sanitized;

        log.info("Dropping tenant '{}': schema={}", tenantId, schemaName);

        // 1. Drop Postgres schema
        try {
            platformDsl.execute(
                "DROP SCHEMA IF EXISTS " + schemaName + " CASCADE"
            );
            log.debug("Dropped Postgres schema '{}'", schemaName);
        } catch (Exception e) {
            throw new OperioServiceException(
                "Failed to drop Postgres schema '" +
                    schemaName +
                    "' for tenant '" +
                    tenantId +
                    "'",
                e
            );
        }

        // 2. Remove Redis ACL user (if Redis is configured)
        if (redisConfig != null) {
            try {
                String redisAclUser =
                    TenantAwareExecuteListener.SCHEMA_PREFIX + sanitized;

                // ACL commands are only available on Jedis (not JedisPooled /
                // UnifiedJedis) in Jedis 5.x. Create a short-lived connection.
                try (Jedis jedis = createJedisConnection()) {
                    jedis.aclDelUser(redisAclUser);
                }

                log.debug("Removed Redis ACL user '{}'", redisAclUser);
            } catch (Exception e) {
                log.warn(
                    "Failed to remove Redis ACL user for tenant '{}'. " +
                        "Schema was dropped successfully — Redis cleanup can be retried.",
                    tenantId,
                    e
                );
                // Don't fail — schema is already dropped
            }
        }

        log.info("Tenant '{}' infrastructure torn down", tenantId);
    }

    // -----------------------------------------------------------------------
    // Bulk migration
    // -----------------------------------------------------------------------

    /**
     * Runs pending consent Flyway migrations against <em>all</em> active
     * tenants' schemas. Called on application startup / deploy to bring
     * every tenant's schema up to the latest migration version.
     *
     * <p>Tenants with status other than {@code "active"} are skipped.
     * Migration failures for individual tenants are logged but do not
     * abort the entire operation.
     *
     * @return the number of tenants successfully migrated
     */
    public int migrateAllTenants() {
        var tenants = tenantRepository.findAll();
        int migrated = 0;

        for (var tenant : tenants) {
            if (!"active".equals(tenant.getStatus())) {
                log.debug(
                    "Skipping inactive tenant '{}' (status={})",
                    tenant.getTenantId(),
                    tenant.getStatus()
                );
                continue;
            }

            String schemaName = tenant.getSchemaName();
            try {
                Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schemaName)
                    .defaultSchema(schemaName)
                    .locations(consentMigrationLocation)
                    .load()
                    .migrate();
                migrated++;
                log.debug(
                    "Migrated tenant '{}' schema '{}'",
                    tenant.getTenantId(),
                    schemaName
                );
            } catch (Exception e) {
                log.error(
                    "Failed to migrate tenant '{}' schema '{}': {}",
                    tenant.getTenantId(),
                    schemaName,
                    e.getMessage(),
                    e
                );
            }
        }

        log.info(
            "Migrated {}/{} active tenant schemas",
            migrated,
            tenants
                .stream()
                .filter(t -> "active".equals(t.getStatus()))
                .count()
        );
        return migrated;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Validates that a tenant ID contains only safe characters for use in
     * SQL identifiers and Redis key patterns.
     *
     * @param tenantId the raw tenant ID
     * @return the validated tenant ID (unchanged if valid)
     * @throws IllegalArgumentException if the tenant ID is invalid
     */
    static String sanitizeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException(
                "Tenant ID must not be null or blank"
            );
        }
        if (!VALID_TENANT_ID.matcher(tenantId).matches()) {
            throw new IllegalArgumentException(
                "Tenant ID contains illegal characters: '" +
                    tenantId +
                    "'. Only alphanumeric characters and underscores are allowed."
            );
        }
        return tenantId;
    }

    /**
     * Creates a short-lived {@link Jedis} connection for ACL operations.
     *
     * <p>ACL commands ({@code ACL SETUSER}, {@code ACL DELUSER}) are only
     * available on the {@link Jedis} class in Jedis 5.x — they are not
     * exposed on {@link redis.clients.jedis.JedisPooled} or
     * {@link redis.clients.jedis.UnifiedJedis}. This method creates a
     * direct connection using the host/port/password from the stored
     * {@link RedisPluginConfig}.
     *
     * <p>Callers must close the returned {@link Jedis} instance when done
     * (use try-with-resources).
     *
     * @return a connected and authenticated {@link Jedis} instance
     */
    private Jedis createJedisConnection() {
        Jedis jedis = new Jedis(redisConfig.host(), redisConfig.port());
        if (
            redisConfig.password() != null && !redisConfig.password().isBlank()
        ) {
            jedis.auth(redisConfig.password());
        }
        return jedis;
    }

    /**
     * Generates a cryptographically secure random password for Redis ACL users.
     *
     * @return a 32-character base64url-encoded password
     */
    private static String generateSecurePassword() {
        byte[] bytes = new byte[24]; // 24 bytes = 32 base64 chars
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Computes the SHA-256 hex digest of the given input string.
     *
     * @param input the string to hash
     * @return the lowercase hex-encoded SHA-256 digest
     */
    static String sha256Hex(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                input.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            var sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available on all JVMs
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
