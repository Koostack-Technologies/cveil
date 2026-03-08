package com.koostack.operio.platform.tenant.jooq;

import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.platform.core.TenantManagerService;
import com.koostack.operio.plugin.jooq.common.TenantAwareExecuteListener;
import com.koostack.operio.plugin.platform.keycloak.KeycloakRealmProvisioner;
import com.koostack.operio.plugin.redis.common.RedisClientFactory;
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
 * Tenant infrastructure lifecycle manager — provisions and tears down the per-tenant Postgres
 * schema and Redis ACL user.
 *
 * <p>Implements {@link TenantManagerService} to participate in the Operio plugin SPI lifecycle.
 *
 * <h2>Provisioning ({@link #provisionTenant})</h2>
 *
 * <ol>
 *   <li>Creates Postgres schema {@code t_{tenantId}} via {@code CREATE SCHEMA IF NOT EXISTS}.
 *   <li>Runs consent Flyway migrations against the new schema.
 *   <li>If a Redis config is provided, provisions a Redis ACL user ({@code t_{tenantId}})
 *       restricted to the tenant's key prefix.
 * </ol>
 *
 * <h2>Teardown ({@link #dropTenant})</h2>
 *
 * <ol>
 *   <li>Drops the Postgres schema {@code t_{tenantId}} with {@code CASCADE}.
 *   <li>If a Redis config is provided, removes the Redis ACL user.
 * </ol>
 */
public class JooqTenantManagerService implements TenantManagerService<JooqTenantManagerConfig> {

    private static final Logger log = LoggerFactory.getLogger(JooqTenantManagerService.class);

    private static final Pattern VALID_TENANT_ID = Pattern.compile("^[a-zA-Z0-9_]+$");

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private DSLContext platformDsl;
    private DataSource tenantDataSource;
    private TenantRepository tenantRepository;
    private RedisPluginConfig redisConfig;
    private volatile String appName;
    private String consentMigrationLocation;
    private String eventMigrationLocation;
    private String metricsMigrationLocation;
    private KeycloakRealmProvisioner keycloakProvisioner;
    private String tenantAdminEmail;

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "jooq-tenant-manager";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqTenantManagerConfig> getConfigurationType() {
        return JooqTenantManagerConfig.class;
    }

    @Override
    public void configure(JooqTenantManagerConfig config) {
        this.platformDsl = config.platformDsl();
        this.tenantDataSource = config.tenantDataSource();
        this.tenantRepository = config.tenantRepository();
        this.redisConfig = config.redisConfig();
        this.appName = config.appName() != null ? config.appName() : "operio";
        this.consentMigrationLocation =
                config.consentMigrationLocation() != null
                        ? config.consentMigrationLocation()
                        : "classpath:db/migration/consent";
        this.eventMigrationLocation = config.eventMigrationLocation();
        this.metricsMigrationLocation = config.metricsMigrationLocation();
        this.keycloakProvisioner = config.keycloakProvisioner();
        this.tenantAdminEmail = config.tenantAdminEmail();
        log.info("JooqTenantManagerService configured");
    }

    @Override
    public boolean isReady() {
        return platformDsl != null && tenantDataSource != null;
    }

    /** Returns the underlying {@link TenantRepository} for direct DB access. */
    public TenantRepository getTenantRepository() {
        return tenantRepository;
    }

    /** Executes a DDL statement against the tenant database. */
    private void executeTenantDdl(String sql) {
        try (var conn = tenantDataSource.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new OperioServiceException("Tenant DDL failed: " + sql, e);
        }
    }

    // -----------------------------------------------------------------------
    // Result records
    // -----------------------------------------------------------------------

    public record ProvisionResult(
            String schemaName,
            String redisAclUser,
            String redisAclPassword,
            String redisAclPassHash,
            String keycloakRealmId,
            String keycloakClientSecret) {

        /** Backward-compatible constructor without Keycloak fields. */
        public ProvisionResult(
                String schemaName,
                String redisAclUser,
                String redisAclPassword,
                String redisAclPassHash) {
            this(schemaName, redisAclUser, redisAclPassword, redisAclPassHash, null, null);
        }
    }

    public record ReprovisionResult(
            String tenantId,
            String redisAclUser,
            String redisAclPassword,
            String redisAclPassHash) {}

    // -----------------------------------------------------------------------
    // Provisioning
    // -----------------------------------------------------------------------

    public ProvisionResult provisionTenant(String tenantId) {
        String sanitized = sanitizeTenantId(tenantId);
        String schemaName = TenantAwareExecuteListener.SCHEMA_PREFIX + sanitized;

        log.info("Provisioning tenant '{}': schema={}", tenantId, schemaName);

        try {
            executeTenantDdl("CREATE SCHEMA IF NOT EXISTS " + schemaName);
            log.debug("Created Postgres schema '{}'", schemaName);
        } catch (Exception e) {
            throw new OperioServiceException(
                    "Failed to create Postgres schema '"
                            + schemaName
                            + "' for tenant '"
                            + tenantId
                            + "'",
                    e);
        }

        try {
            Flyway.configure()
                    .dataSource(tenantDataSource)
                    .schemas(schemaName)
                    .defaultSchema(schemaName)
                    .locations(consentMigrationLocation)
                    .load()
                    .migrate();
            log.debug("Applied consent migrations to schema '{}'", schemaName);
        } catch (Exception e) {
            try {
                executeTenantDdl("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
            } catch (Exception cleanup) {
                log.warn(
                        "Failed to clean up schema '{}' after migration failure",
                        schemaName,
                        cleanup);
            }
            throw new OperioServiceException(
                    "Failed to apply consent migrations to schema '"
                            + schemaName
                            + "' for tenant '"
                            + tenantId
                            + "'",
                    e);
        }

        // 2. Per-tenant event store schema (t_{tenantId}_event)
        String eventSchemaName = schemaName + TenantAwareExecuteListener.EVENT_SCHEMA_SUFFIX;
        if (eventMigrationLocation != null) {
            try {
                executeTenantDdl("CREATE SCHEMA IF NOT EXISTS " + eventSchemaName);
                Flyway.configure()
                        .dataSource(tenantDataSource)
                        .schemas(eventSchemaName)
                        .defaultSchema(eventSchemaName)
                        .locations(eventMigrationLocation)
                        .load()
                        .migrate();
                log.debug("Applied event migrations to schema '{}'", eventSchemaName);
            } catch (Exception e) {
                try {
                    executeTenantDdl("DROP SCHEMA IF EXISTS " + eventSchemaName + " CASCADE");
                } catch (Exception cleanup) {
                    log.warn(
                            "Failed to clean up event schema '{}' after migration failure",
                            eventSchemaName,
                            cleanup);
                }
                throw new OperioServiceException(
                        "Failed to apply event migrations to schema '"
                                + eventSchemaName
                                + "' for tenant '"
                                + tenantId
                                + "'",
                        e);
            }
        }

        String redisAclUser = null;
        String redisAclPassword = null;
        String redisAclPassHash = null;
        if (redisConfig != null) {
            try {
                redisAclUser = TenantAwareExecuteListener.SCHEMA_PREFIX + sanitized;
                String password = generateSecurePassword();
                redisAclPassHash = sha256Hex(password);

                String keyPattern = tenantId + ":*";

                try (Jedis jedis = createJedisConnection()) {
                    jedis.aclSetUser(redisAclUser, "reset");
                    jedis.aclSetUser(
                            redisAclUser,
                            "on",
                            ">" + password,
                            "~" + keyPattern,
                            "+@read",
                            "+@write",
                            "+@connection",
                            "-@admin",
                            "-@dangerous");
                }

                redisAclPassword = password;

                log.debug(
                        "Provisioned Redis ACL user '{}' with key pattern '{}'",
                        redisAclUser,
                        keyPattern);
            } catch (Exception e) {
                log.warn(
                        "Failed to provision Redis ACL user for tenant '{}'. "
                                + "Schema was created successfully — Redis ACL can be retried.",
                        tenantId,
                        e);
            }
        }

        // 4. Provision tenant metrics schema (if metrics migration is configured)
        if (metricsMigrationLocation != null) {
            String metricsSchema = schemaName + "_metrics";
            try {
                executeTenantDdl("CREATE SCHEMA IF NOT EXISTS " + metricsSchema);
                Flyway.configure()
                        .dataSource(tenantDataSource)
                        .schemas(metricsSchema)
                        .defaultSchema(metricsSchema)
                        .locations(metricsMigrationLocation)
                        .load()
                        .migrate();
                log.debug("Applied metrics migrations to schema '{}'", metricsSchema);
            } catch (Exception e) {
                log.warn(
                        "Failed to provision metrics schema '{}' for tenant '{}'. "
                                + "Core schema was created successfully — metrics can be retried.",
                        metricsSchema,
                        tenantId,
                        e);
            }
        }

        // 5. Provision Keycloak realm (if provisioner is configured)
        String keycloakRealmId = null;
        String keycloakClientSecret = null;
        if (keycloakProvisioner != null) {
            String realmId = "t_" + sanitized;
            try {
                KeycloakRealmProvisioner.ProvisionResult kcResult =
                        keycloakProvisioner.provisionRealm(realmId);
                keycloakRealmId = realmId;
                keycloakClientSecret = kcResult.clientSecret();
                log.info("Provisioned Keycloak realm '{}' for tenant '{}'", realmId, tenantId);

                // Create root admin user with UPDATE_PASSWORD required action
                if (tenantAdminEmail != null && !tenantAdminEmail.isBlank()) {
                    try {
                        keycloakProvisioner.createRootUser(realmId, tenantAdminEmail);
                        log.info(
                                "Created root admin user in realm '{}': email={}",
                                realmId,
                                tenantAdminEmail);
                    } catch (Exception e) {
                        log.warn(
                                "Failed to create root admin user in realm '{}' — "
                                        + "realm was provisioned. User can be created manually.",
                                realmId,
                                e);
                    }
                }
            } catch (Exception e) {
                log.warn(
                        "Failed to provision Keycloak realm for tenant '{}'. "
                                + "Schema was created successfully — Keycloak can be retried.",
                        tenantId,
                        e);
            }
        }

        log.info(
                "Tenant '{}' provisioned successfully: schema={}, redisUser={}, keycloakRealm={}",
                tenantId,
                schemaName,
                redisAclUser,
                keycloakRealmId);

        return new ProvisionResult(
                schemaName,
                redisAclUser,
                redisAclPassword,
                redisAclPassHash,
                keycloakRealmId,
                keycloakClientSecret);
    }

    // -----------------------------------------------------------------------
    // Redis ACL Reprovisioning
    // -----------------------------------------------------------------------

    public ReprovisionResult reprovisionRedisAcl(String tenantId) {
        if (redisConfig == null) {
            throw new IllegalStateException(
                    "Cannot reprovision Redis ACL: Redis is not configured.");
        }

        String sanitized = sanitizeTenantId(tenantId);
        String redisAclUser = TenantAwareExecuteListener.SCHEMA_PREFIX + sanitized;
        String password = generateSecurePassword();
        String passHash = sha256Hex(password);
        String keyPattern = tenantId + ":*";

        log.info(
                "Reprovisioning Redis ACL for tenant '{}' (aclUser='{}', keyPattern='{}')",
                tenantId,
                redisAclUser,
                keyPattern);

        try (Jedis jedis = createJedisConnection()) {
            jedis.aclSetUser(redisAclUser, "reset");
            jedis.aclSetUser(
                    redisAclUser,
                    "on",
                    ">" + password,
                    "~" + keyPattern,
                    "+@read",
                    "+@write",
                    "+@connection",
                    "-@admin",
                    "-@dangerous");
            log.debug(
                    "Redis ACL user '{}' reset with new credentials and key pattern '{}'",
                    redisAclUser,
                    keyPattern);
        } catch (Exception e) {
            throw new OperioServiceException(
                    "Failed to reprovision Redis ACL user '"
                            + redisAclUser
                            + "' for tenant '"
                            + tenantId
                            + "'",
                    e);
        }

        try {
            int updated =
                    tenantRepository.updateRedisAclCredentials(
                            tenantId, redisAclUser, password, passHash, "reprovision-redis-acl");
            if (updated == 0) {
                log.warn(
                        "Tenant '{}' not found in platform DB — Redis ACL was "
                                + "updated but no database row was modified.",
                        tenantId);
            }
        } catch (Exception e) {
            log.error(
                    "Redis ACL for tenant '{}' was updated but the database "
                            + "update failed. Retry to reconcile.",
                    tenantId,
                    e);
            throw new OperioServiceException(
                    "Failed to update Redis ACL credentials in database for "
                            + "tenant '"
                            + tenantId
                            + "' (Redis was already updated — retry to reconcile)",
                    e);
        }

        log.info("Redis ACL reprovisioned for tenant '{}' (aclUser='{}')", tenantId, redisAclUser);

        return new ReprovisionResult(tenantId, redisAclUser, password, passHash);
    }

    public int reprovisionAllRedisAcls() {
        if (redisConfig == null) {
            throw new IllegalStateException(
                    "Cannot reprovision Redis ACLs: Redis is not configured.");
        }

        var tenants = tenantRepository.findAll();
        int provisioned = 0;
        int skipped = 0;
        int failed = 0;

        for (var tenant : tenants) {
            if (!"active".equals(tenant.getStatus())) {
                log.debug(
                        "Skipping inactive tenant '{}' (status={})",
                        tenant.getTenantId(),
                        tenant.getStatus());
                skipped++;
                continue;
            }

            if (tenant.getRedisAclPassword() != null && !tenant.getRedisAclPassword().isBlank()) {
                log.debug(
                        "Tenant '{}' already has Redis ACL credentials — skipping",
                        tenant.getTenantId());
                skipped++;
                continue;
            }

            try {
                reprovisionRedisAcl(tenant.getTenantId());
                provisioned++;
            } catch (Exception e) {
                log.error(
                        "Failed to reprovision Redis ACL for tenant '{}': {}",
                        tenant.getTenantId(),
                        e.getMessage(),
                        e);
                failed++;
            }
        }

        log.info(
                "Redis ACL batch reprovision complete: provisioned={}, skipped={}, failed={},"
                        + " total={}",
                provisioned,
                skipped,
                failed,
                tenants.size());
        return provisioned;
    }

    // -----------------------------------------------------------------------
    // Teardown
    // -----------------------------------------------------------------------

    public void dropTenant(String tenantId) {
        String sanitized = sanitizeTenantId(tenantId);
        String schemaName = TenantAwareExecuteListener.SCHEMA_PREFIX + sanitized;

        log.info("Dropping tenant '{}': schema={}", tenantId, schemaName);

        try {
            executeTenantDdl("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
            log.debug("Dropped Postgres schema '{}'", schemaName);
        } catch (Exception e) {
            throw new OperioServiceException(
                    "Failed to drop Postgres schema '"
                            + schemaName
                            + "' for tenant '"
                            + tenantId
                            + "'",
                    e);
        }

        // Drop per-tenant event store schema
        String eventSchema = schemaName + TenantAwareExecuteListener.EVENT_SCHEMA_SUFFIX;
        try {
            executeTenantDdl("DROP SCHEMA IF EXISTS " + eventSchema + " CASCADE");
            log.debug("Dropped event schema '{}'", eventSchema);
        } catch (Exception e) {
            log.warn(
                    "Failed to drop event schema '{}' for tenant '{}' — can be retried",
                    eventSchema,
                    tenantId,
                    e);
        }

        if (redisConfig != null) {
            try {
                String redisAclUser = TenantAwareExecuteListener.SCHEMA_PREFIX + sanitized;
                try (Jedis jedis = createJedisConnection()) {
                    jedis.aclDelUser(redisAclUser);
                }
                log.debug("Removed Redis ACL user '{}'", redisAclUser);
            } catch (Exception e) {
                log.warn(
                        "Failed to remove Redis ACL user for tenant '{}'. "
                                + "Schema was dropped successfully — Redis cleanup can be retried.",
                        tenantId,
                        e);
            }
        }

        // Drop tenant metrics schema
        if (metricsMigrationLocation != null) {
            String metricsSchema = schemaName + "_metrics";
            try {
                executeTenantDdl("DROP SCHEMA IF EXISTS " + metricsSchema + " CASCADE");
                log.debug("Dropped metrics schema '{}'", metricsSchema);
            } catch (Exception e) {
                log.warn(
                        "Failed to drop metrics schema '{}' for tenant '{}' — can be retried",
                        metricsSchema,
                        tenantId,
                        e);
            }
        }

        // Deprovision Keycloak realm
        if (keycloakProvisioner != null) {
            String realmId = "t_" + sanitized;
            try {
                keycloakProvisioner.deprovisionRealm(realmId);
                log.debug("Deprovisioned Keycloak realm '{}'", realmId);
            } catch (Exception e) {
                log.warn(
                        "Failed to deprovision Keycloak realm '{}' for tenant '{}' — "
                                + "can be retried",
                        realmId,
                        tenantId,
                        e);
            }
        }

        log.info("Tenant '{}' infrastructure torn down", tenantId);
    }

    // -----------------------------------------------------------------------
    // Bulk migration
    // -----------------------------------------------------------------------

    public int migrateAllTenants() {
        var tenants = tenantRepository.findAll();
        int migrated = 0;

        for (var tenant : tenants) {
            if (!"active".equals(tenant.getStatus())) {
                log.debug(
                        "Skipping inactive tenant '{}' (status={})",
                        tenant.getTenantId(),
                        tenant.getStatus());
                continue;
            }

            String schemaName = tenant.getSchemaName();
            try {
                Flyway.configure()
                        .dataSource(tenantDataSource)
                        .schemas(schemaName)
                        .defaultSchema(schemaName)
                        .locations(consentMigrationLocation)
                        .load()
                        .migrate();
                migrated++;
                log.debug("Migrated tenant '{}' schema '{}'", tenant.getTenantId(), schemaName);
            } catch (Exception e) {
                log.error(
                        "Failed to migrate tenant '{}' schema '{}': {}",
                        tenant.getTenantId(),
                        schemaName,
                        e.getMessage(),
                        e);
            }

            // Migrate per-tenant event store schema
            if (eventMigrationLocation != null) {
                String eventSchema = schemaName + TenantAwareExecuteListener.EVENT_SCHEMA_SUFFIX;
                try {
                    executeTenantDdl("CREATE SCHEMA IF NOT EXISTS " + eventSchema);
                    Flyway.configure()
                            .dataSource(tenantDataSource)
                            .schemas(eventSchema)
                            .defaultSchema(eventSchema)
                            .locations(eventMigrationLocation)
                            .load()
                            .migrate();
                    log.debug(
                            "Migrated tenant '{}' event schema '{}'",
                            tenant.getTenantId(),
                            eventSchema);
                } catch (Exception e) {
                    log.error(
                            "Failed to migrate tenant '{}' event schema '{}': {}",
                            tenant.getTenantId(),
                            eventSchema,
                            e.getMessage(),
                            e);
                }
            }

            // Migrate tenant metrics schema
            if (metricsMigrationLocation != null) {
                String metricsSchema = schemaName + "_metrics";
                try {
                    executeTenantDdl("CREATE SCHEMA IF NOT EXISTS " + metricsSchema);
                    Flyway.configure()
                            .dataSource(tenantDataSource)
                            .schemas(metricsSchema)
                            .defaultSchema(metricsSchema)
                            .locations(metricsMigrationLocation)
                            .load()
                            .migrate();
                    log.debug(
                            "Migrated tenant '{}' metrics schema '{}'",
                            tenant.getTenantId(),
                            metricsSchema);
                } catch (Exception e) {
                    log.error(
                            "Failed to migrate tenant '{}' metrics schema '{}': {}",
                            tenant.getTenantId(),
                            metricsSchema,
                            e.getMessage(),
                            e);
                }
            }
        }

        log.info(
                "Migrated {}/{} active tenant schemas",
                migrated,
                tenants.stream().filter(t -> "active".equals(t.getStatus())).count());
        return migrated;
    }

    // -----------------------------------------------------------------------
    // App name
    // -----------------------------------------------------------------------

    public void setAppName(String appName) {
        this.appName = appName != null ? appName : "operio";
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    static String sanitizeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID must not be null or blank");
        }
        if (!VALID_TENANT_ID.matcher(tenantId).matches()) {
            throw new IllegalArgumentException(
                    "Tenant ID contains illegal characters: '"
                            + tenantId
                            + "'. Only alphanumeric characters and underscores are allowed.");
        }
        return tenantId;
    }

    private Jedis createJedisConnection() {
        RedisClientFactory.TlsConfig tls = redisConfig.tlsConfig();
        Jedis jedis;
        if (tls != null && tls.isEnabled()) {
            try {
                javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
                javax.net.ssl.TrustManager[] tm;
                if (tls.isVerifyPeer()
                        && tls.getCaCertPath() != null
                        && !tls.getCaCertPath().isBlank()) {
                    java.nio.file.Path caPath = java.nio.file.Paths.get(tls.getCaCertPath());
                    byte[] certBytes = java.nio.file.Files.readAllBytes(caPath);
                    java.security.cert.CertificateFactory cf =
                            java.security.cert.CertificateFactory.getInstance("X.509");
                    java.security.KeyStore ks =
                            java.security.KeyStore.getInstance(
                                    java.security.KeyStore.getDefaultType());
                    ks.load(null, null);
                    int i = 1;
                    for (java.security.cert.Certificate cert :
                            cf.generateCertificates(new java.io.ByteArrayInputStream(certBytes))) {
                        ks.setCertificateEntry("cert-" + i++, cert);
                    }
                    javax.net.ssl.TrustManagerFactory tmf =
                            javax.net.ssl.TrustManagerFactory.getInstance(
                                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(ks);
                    tm = tmf.getTrustManagers();
                } else {
                    tm =
                            new javax.net.ssl.TrustManager[] {
                                new javax.net.ssl.X509TrustManager() {
                                    public java.security.cert.X509Certificate[]
                                            getAcceptedIssuers() {
                                        return null;
                                    }

                                    public void checkClientTrusted(
                                            java.security.cert.X509Certificate[] c, String a) {}

                                    public void checkServerTrusted(
                                            java.security.cert.X509Certificate[] c, String a) {}
                                }
                            };
                }
                sslContext.init(null, tm, new java.security.SecureRandom());
                jedis =
                        new Jedis(
                                redisConfig.host(),
                                redisConfig.port(),
                                true,
                                sslContext.getSocketFactory(),
                                null,
                                null);
            } catch (Exception e) {
                throw new OperioServiceException(
                        "Failed to create TLS Jedis connection for ACL operations", e);
            }
        } else {
            jedis = new Jedis(redisConfig.host(), redisConfig.port());
        }
        if (redisConfig.password() != null && !redisConfig.password().isBlank()) {
            jedis.auth(redisConfig.password());
        }
        return jedis;
    }

    private static String generateSecurePassword() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
