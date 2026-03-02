package com.koostack.operio.plugin.platform.jooq;

import com.koostack.operio.core.Operio;
import com.koostack.operio.core.models.ApiKeyInfo;
import com.koostack.operio.core.models.IdpConfigInfo;
import com.koostack.operio.core.models.TenantInfo;
import com.koostack.operio.core.exception.OperioServiceException;
import com.koostack.operio.core.service.PlatformAdminService;
import com.koostack.operio.plugin.jooq.common.JooqDataSourceFactory;
import com.koostack.operio.plugin.platform.jooq.generated.tables.pojos.ApiKey;
import com.koostack.operio.plugin.platform.jooq.generated.tables.pojos.IdpConfig;
import com.koostack.operio.plugin.platform.jooq.generated.tables.pojos.Tenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * jOOQ-backed {@link PlatformAdminService} implementation — the administrative
 * control plane for the Operio platform.
 *
 * <p>Owns the shared platform schema that manages tenants, API keys, and IdP
 * configurations. This service targets the <strong>shared platform schema</strong>
 * (never tenant-routed). The platform {@link DSLContext} is created with
 * {@link JooqContextFactory#createContext} (plain, non-tenant-aware).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li><strong>Tenant lifecycle</strong> — create/delete/query tenants.
 *       Creating a tenant delegates to {@link TenantManager} to provision a
 *       dedicated Postgres schema ({@code t_{tenantId}}) with Flyway-applied
 *       consent migrations and a tenant-scoped Redis ACL user.</li>
 *   <li><strong>API key management</strong> — create/revoke/list API keys.
 *       Keys are stored as SHA-256 hashes; the raw key is returned only once
 *       at creation time.</li>
 *   <li><strong>IdP configuration</strong> — configure/query/list external
 *       identity providers per tenant.</li>
 * </ul>
 *
 * <h2>Shared Infrastructure</h2>
 * <p>{@link ApiKeyTokenVerifier} shares the same platform {@link DSLContext}
 * and {@link ApiKeyRepository} with this service. In production, the starter
 * wires them from a single {@link PlatformJooqConfig}:
 * <pre>{@code
 * JooqPlatformAdminService platformAdmin = new JooqPlatformAdminService();
 * platformAdmin.configure(platformConfig);
 * ApiKeyTokenVerifier tokenVerifier = new ApiKeyTokenVerifier(
 *     platformAdmin.platformDslContext(), platformAdmin.apiKeyRepository());
 * }</pre>
 *
 * <h2>API Key Format</h2>
 * <p>Raw keys follow the format {@code opr_live_{random}} where {@code random}
 * is 32 characters of URL-safe base64. The first 8 characters of the raw key
 * serve as the {@code keyPrefix} for display and revocation. Only the SHA-256
 * hash is stored in the database — the raw key is returned exactly once.
 *
 * @see PlatformAdminService
 * @see ApiKeyTokenVerifier
 * @see TenantManager
 */
public class JooqPlatformAdminService
    implements PlatformAdminService<PlatformJooqConfig>, Operio.AppNameAware
{

    private static final Logger log = LoggerFactory.getLogger(
        JooqPlatformAdminService.class
    );

    private static final String API_KEY_PREFIX_LITERAL = "opr_live_";
    private static final int KEY_PREFIX_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private DSLContext platformDslContext;
    private TenantRepository tenantRepository;
    private ApiKeyRepository apiKeyRepository;
    private IdpConfigRepository idpConfigRepository;
    private TenantManager tenantManager;

    // -----------------------------------------------------------------------
    // Plugin SPI
    // -----------------------------------------------------------------------

    @Override
    public String getName() {
        return "jooq-platform";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<PlatformJooqConfig> getConfigurationType() {
        return PlatformJooqConfig.class;
    }

    /**
     * Configures this service from a {@link PlatformJooqConfig}.
     *
     * <p>Creates the platform {@link DSLContext} (separate from the consent
     * DSLContext — targets the shared platform schema, never tenant-routed),
     * runs Flyway platform migrations, creates all repositories, and
     * creates the {@link TenantManager}.
     *
     * @param config the platform configuration (JDBC URL, dialect, optional
     *               Redis config)
     */
    @Override
    public void configure(PlatformJooqConfig config) {
        // Create the DataSource — used by Flyway for the old admin migrations.
        DataSource dataSource = JooqDataSourceFactory.createDataSource(
            config,
            "operio-platform-jooq"
        );

        // Run platform Flyway migrations (separate history table to avoid
        // checksum collisions with consent migrations in the same DB).
        // baselineOnMigrate is required because the consent module's tables
        // already exist in the public schema — without it Flyway refuses to
        // run against a non-empty schema that has no history table yet.
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/platform")
            .table("flyway_schema_history_platform")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()
            .migrate();

        // Create a DataSource with search_path so that unqualified table
        // names (e.g. "tenant") resolve to the CQRS projection schema
        // (platform_core) first, then fall back to public for tables
        // that only exist there (api_key, idp_config, etc.).
        SQLDialect sqlDialect = SQLDialect.valueOf(
            config.dialect().toUpperCase()
        );
        String jdbcUrl = config.connectionString();
        if (jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql")) {
            String sep = jdbcUrl.contains("?") ? "&" : "?";
            jdbcUrl = jdbcUrl + sep + "stringtype=unspecified";
        }
        com.zaxxer.hikari.HikariConfig hikariCfg = new com.zaxxer.hikari.HikariConfig();
        hikariCfg.setJdbcUrl(jdbcUrl);
        hikariCfg.setPoolName("operio-platform-admin");
        if (config.username() != null) hikariCfg.setUsername(config.username());
        if (config.password() != null) hikariCfg.setPassword(config.password());
        hikariCfg.setConnectionInitSql(
            "SET search_path TO platform_core, public"
        );
        DataSource adminDataSource = new com.zaxxer.hikari.HikariDataSource(hikariCfg);

        // renderSchema=false prevents jOOQ from emitting "public"."tenant" —
        // instead it emits just "tenant" which search_path resolves to
        // platform_core.tenant (the CQRS read model).
        Settings settings = new Settings()
            .withRenderNameCase(RenderNameCase.LOWER)
            .withRenderSchema(false);
        this.platformDslContext = DSL.using(adminDataSource, sqlDialect, settings);

        this.tenantRepository = new TenantRepository(
            platformDslContext,
            config.encryptionKey()
        );
        this.apiKeyRepository = new ApiKeyRepository(platformDslContext);
        this.idpConfigRepository = new IdpConfigRepository(platformDslContext);

        this.tenantManager = new TenantManager(
            platformDslContext,
            dataSource,
            tenantRepository,
            config.redis(),
            "operio",
            "classpath:db/migration/consent"
        );

        boolean encryptionEnabled =
            config.encryptionKey() != null && !config.encryptionKey().isBlank();
        if (!encryptionEnabled) {
            log.warn(
                "JooqPlatformAdminService: redis_acl_password encryption is " +
                    "DISABLED — passwords will be stored in plaintext. Set the " +
                    "OPERIO_DB_ENCRYPTION_KEY environment variable to enable " +
                    "encryption at rest."
            );
        }
        log.info(
            "JooqPlatformAdminService configured (redis={}, passwordEncryption={})",
            config.redis() != null ? "enabled" : "disabled",
            encryptionEnabled ? "enabled" : "DISABLED"
        );
    }

    /**
     * Returns {@code true} if this service is configured and the platform
     * database is reachable.
     *
     * @return {@code true} if the plugin is ready to serve requests
     */
    @Override
    public boolean isReady() {
        if (platformDslContext == null) {
            return false;
        }
        try {
            platformDslContext.selectOne().fetch();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Accessors — shared infrastructure
    // -----------------------------------------------------------------------

    /**
     * Returns the platform {@link DSLContext} (non-tenant-aware).
     *
     * <p>Used by {@link ApiKeyTokenVerifier} to share the same connection
     * pool and repositories.
     *
     * @return the platform DSL context
     */
    public DSLContext platformDslContext() {
        return platformDslContext;
    }

    /** Returns the tenant repository. */
    public TenantRepository tenantRepository() {
        return tenantRepository;
    }

    /** Returns the API key repository. */
    public ApiKeyRepository apiKeyRepository() {
        return apiKeyRepository;
    }

    /** Returns the IdP config repository. */
    public IdpConfigRepository idpConfigRepository() {
        return idpConfigRepository;
    }

    /** Returns the tenant manager. */
    public TenantManager tenantManager() {
        return tenantManager;
    }

    @Override
    public void setAppName(String appName) {
        if (tenantManager != null) {
            tenantManager.setAppName(appName);
        }
    }

    /**
     * Sets the per-tenant stream consumer on the underlying {@link TenantManager}.
     *
     * <p>Must be called after {@link #configure} and before
     * {@link TenantManager#migrateAllTenants()} so that startup reconciliation
     * can add tenants to the consumer.
     *
     * @param consumer the stream consumer, or {@code null} to disable
     */
    public void setStreamConsumer(
        com.koostack.operio.tenant.event.jooq.TenantStreamConsumer consumer
    ) {
        if (tenantManager != null) {
            tenantManager.setStreamConsumer(consumer);
        }
    }

    // -----------------------------------------------------------------------
    // Tenant lifecycle
    // -----------------------------------------------------------------------

    /**
     * Create a tenant imperatively (inserts row + provisions infra).
     *
     * <p>Prefer {@code CoreCommandService.createTenant()} for event-sourced
     * tenant creation. This method is retained for backward compatibility
     * and test helpers.
     */
    public void createTenant(String tenantId, String displayName, String plan) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new OperioServiceException(
                "Tenant ID must not be null or blank"
            );
        }

        // Check for duplicate
        if (tenantRepository.findById(tenantId).isPresent()) {
            throw new OperioServiceException(
                "Tenant already exists: '" + tenantId + "'"
            );
        }

        // Provision infrastructure first (schema + Redis ACL)
        TenantManager.ProvisionResult result = tenantManager.provisionTenant(
            tenantId
        );

        // Insert tenant row (includes plaintext Redis ACL password so that
        // per-tenant JedisPooled connections can authenticate as the tenant's
        // ACL user at runtime, enforcing server-side key isolation)
        tenantRepository.insert(
            tenantId,
            displayName,
            plan != null ? plan : "free",
            result.schemaName(),
            result.redisAclUser(),
            result.redisAclPassword(),
            result.redisAclPassHash(),
            "platform-admin"
        );

        log.info(
            "Tenant '{}' created (plan={}, schema={})",
            tenantId,
            plan,
            result.schemaName()
        );
    }

    /**
     * Delete a tenant imperatively (drops infra + deletes rows).
     *
     * <p>Prefer {@code CoreCommandService.deleteTenant()} for event-sourced
     * tenant deletion. This method is retained for backward compatibility
     * and test helpers.
     */
    public void deleteTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new OperioServiceException(
                "Tenant ID must not be null or blank"
            );
        }

        // Verify tenant exists
        tenantRepository
            .findById(tenantId)
            .orElseThrow(() ->
                new OperioServiceException(
                    "Tenant does not exist: '" + tenantId + "'"
                )
            );

        // Drop infrastructure (schema + Redis ACL)
        tenantManager.dropTenant(tenantId);

        // Delete dependent rows (order matters for FK constraints)
        apiKeyRepository.deleteByTenant(tenantId);
        idpConfigRepository.deleteByTenant(tenantId);

        // Delete the tenant row
        tenantRepository.delete(tenantId);

        log.info("Tenant '{}' deleted", tenantId);
    }

    // -----------------------------------------------------------------------
    // Redis ACL reprovisioning (backfill / rotation)
    // -----------------------------------------------------------------------

    /**
     * Re-provisions Redis ACL credentials for a single tenant.
     *
     * <p>This is the service-layer entry point for the two primary
     * credential management scenarios:
     * <ul>
     *   <li><strong>Backfill</strong> — tenants created before per-tenant
     *       Redis ACL isolation was enabled have
     *       {@code redis_acl_password = NULL}. Calling this method generates
     *       credentials and updates both Redis and the platform database.</li>
     *   <li><strong>Rotation</strong> — periodically rotating the Redis ACL
     *       password for security hygiene. The old password is replaced
     *       atomically in Redis; the database is updated immediately
     *       after.</li>
     * </ul>
     *
     * <p>Delegates to {@link TenantManager#reprovisionRedisAcl(String)}.
     *
     * @param tenantId the tenant whose Redis ACL should be (re-)provisioned
     * @return the reprovisioning result containing the new credentials
     * @throws OperioServiceException   if the tenant does not exist, or
     *                                  Redis/DB operations fail
     * @throws IllegalStateException    if Redis is not configured
     */
    public TenantManager.ReprovisionResult reprovisionRedisAcl(
        String tenantId
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new OperioServiceException(
                "Tenant ID must not be null or blank"
            );
        }

        // Verify tenant exists
        tenantRepository
            .findById(tenantId)
            .orElseThrow(() ->
                new OperioServiceException(
                    "Tenant does not exist: '" + tenantId + "'"
                )
            );

        TenantManager.ReprovisionResult result =
            tenantManager.reprovisionRedisAcl(tenantId);

        log.info(
            "Redis ACL reprovisioned for tenant '{}' (aclUser='{}')",
            tenantId,
            result.redisAclUser()
        );

        return result;
    }

    /**
     * Re-provisions Redis ACL credentials for all active tenants that are
     * currently missing them ({@code redis_acl_password IS NULL}).
     *
     * <p>This is the batch backfill operation for upgrading from the
     * pre-ACL-isolation era. Tenants that already have credentials are
     * skipped. Failures for individual tenants are logged but do not abort
     * the batch.
     *
     * <p>Delegates to {@link TenantManager#reprovisionAllRedisAcls()}.
     *
     * @return the number of tenants successfully provisioned
     * @throws IllegalStateException if Redis is not configured
     */
    public int reprovisionAllRedisAcls() {
        log.info("Starting batch Redis ACL reprovision for all tenants");
        int count = tenantManager.reprovisionAllRedisAcls();
        log.info(
            "Batch Redis ACL reprovision complete: {} tenants provisioned",
            count
        );
        return count;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TenantInfo getTenant(String tenantId) {
        Tenant tenant = tenantRepository
            .findById(tenantId)
            .orElseThrow(() ->
                new OperioServiceException(
                    "Tenant does not exist: '" + tenantId + "'"
                )
            );
        return toTenantInfo(tenant);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<TenantInfo> listTenants() {
        return Collections.unmodifiableList(
            tenantRepository
                .findAll()
                .stream()
                .map(JooqPlatformAdminService::toTenantInfo)
                .toList()
        );
    }

    // -----------------------------------------------------------------------
    // API key management
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Generates a random API key with format {@code opr_live_{random}},
     * computes its SHA-256 hash, stores the hash in the {@code api_key}
     * table, and returns the raw key (shown only once).
     */
    @Override
    public String createApiKey(
        String tenantId,
        String subject,
        Set<String> roles
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new OperioServiceException(
                "Tenant ID must not be null or blank"
            );
        }
        if (subject == null || subject.isBlank()) {
            throw new OperioServiceException(
                "Subject must not be null or blank"
            );
        }

        // Verify tenant exists
        tenantRepository
            .findById(tenantId)
            .orElseThrow(() ->
                new OperioServiceException(
                    "Tenant does not exist: '" + tenantId + "'"
                )
            );

        // Generate raw key
        String rawKey = generateRawApiKey();
        String keyHash = sha256Hex(rawKey);
        String keyPrefix = rawKey.substring(
            0,
            Math.min(rawKey.length(), KEY_PREFIX_LENGTH)
        );

        // Serialize roles
        String rolesStr = (roles != null && !roles.isEmpty())
            ? String.join(",", roles)
            : null;

        // Insert key row
        apiKeyRepository.insert(
            keyHash,
            keyPrefix,
            tenantId,
            subject,
            rolesStr,
            null, // no expiration by default
            "platform-admin"
        );

        log.info(
            "API key created for tenant '{}', subject '{}' (prefix={})",
            tenantId,
            subject,
            keyPrefix
        );

        return rawKey;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revokeApiKey(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new OperioServiceException(
                "Key prefix must not be null or blank"
            );
        }

        ApiKey apiKey = apiKeyRepository
            .findByPrefix(keyPrefix)
            .orElseThrow(() ->
                new OperioServiceException(
                    "No API key found with prefix: '" + keyPrefix + "'"
                )
            );

        apiKeyRepository.updateStatus(
            apiKey.getApiKeyId(),
            "revoked",
            "platform-admin"
        );

        log.info(
            "API key revoked (prefix={}, tenant={})",
            keyPrefix,
            apiKey.getTenantId()
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ApiKeyInfo> listApiKeys(String tenantId) {
        return Collections.unmodifiableList(
            apiKeyRepository
                .findByTenant(tenantId)
                .stream()
                .map(JooqPlatformAdminService::toApiKeyInfo)
                .toList()
        );
    }

    // -----------------------------------------------------------------------
    // IdP configuration
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Upserts an IdP configuration row. The {@code config} map is
     * serialized to a JSON string in the {@code config_json} column.
     * Well-known keys ({@code issuerUrl}, {@code clientId}, {@code jwksUri})
     * are extracted into their own columns for indexed querying.
     */
    @Override
    public void configureIdp(
        String tenantId,
        String provider,
        Map<String, Object> config
    ) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new OperioServiceException(
                "Tenant ID must not be null or blank"
            );
        }
        if (provider == null || provider.isBlank()) {
            throw new OperioServiceException(
                "Provider must not be null or blank"
            );
        }

        // Verify tenant exists
        tenantRepository
            .findById(tenantId)
            .orElseThrow(() ->
                new OperioServiceException(
                    "Tenant does not exist: '" + tenantId + "'"
                )
            );

        // Extract well-known keys from config map
        String issuerUrl =
            config != null ? (String) config.get("issuerUrl") : null;
        String clientId =
            config != null ? (String) config.get("clientId") : null;
        String jwksUri = config != null ? (String) config.get("jwksUri") : null;

        // Serialize the full config map as a simple JSON string
        String configJson = config != null ? toSimpleJson(config) : null;

        idpConfigRepository.upsert(
            tenantId,
            provider,
            issuerUrl,
            clientId,
            jwksUri,
            configJson,
            "platform-admin"
        );

        log.info(
            "IdP configured for tenant '{}', provider '{}'",
            tenantId,
            provider
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IdpConfigInfo getIdpConfig(String tenantId, String provider) {
        IdpConfig idpConfig = idpConfigRepository
            .findByTenantAndProvider(tenantId, provider)
            .orElseThrow(() ->
                new OperioServiceException(
                    "No IdP config found for tenant '" +
                        tenantId +
                        "', provider '" +
                        provider +
                        "'"
                )
            );
        return toIdpConfigInfo(idpConfig);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<IdpConfigInfo> listIdpConfigs(String tenantId) {
        return Collections.unmodifiableList(
            idpConfigRepository
                .findByTenant(tenantId)
                .stream()
                .map(JooqPlatformAdminService::toIdpConfigInfo)
                .toList()
        );
    }

    // -----------------------------------------------------------------------
    // Internal — API key generation
    // -----------------------------------------------------------------------

    /**
     * Generates a raw API key with the format {@code opr_live_{random}}.
     *
     * @return the raw API key — must be transmitted securely and never stored
     */
    private static String generateRawApiKey() {
        byte[] bytes = new byte[24]; // 24 bytes = 32 base64url chars
        SECURE_RANDOM.nextBytes(bytes);
        String random = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes);
        return API_KEY_PREFIX_LITERAL + random;
    }

    /**
     * Computes the SHA-256 hex digest of the given input string.
     *
     * @param input the string to hash
     * @return the lowercase hex-encoded SHA-256 digest
     */
    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    // -----------------------------------------------------------------------
    // Internal — DTO mapping
    // -----------------------------------------------------------------------

    /**
     * Maps a {@link Tenant} POJO to an immutable {@link TenantInfo} DTO.
     */
    private static TenantInfo toTenantInfo(Tenant tenant) {
        return new TenantInfo(
            tenant.getTenantId(),
            tenant.getDisplayName(),
            tenant.getPlan(),
            tenant.getStatus() != null ? tenant.getStatus().toUpperCase() : null,
            tenant.getEvalLimitMonthly(),
            tenant.getSubjectLimit(),
            tenant.getBurstLimitPerMin(),
            tenant.getCreatedAt() != null
                ? tenant.getCreatedAt().toInstant(ZoneOffset.UTC)
                : null
        );
    }

    /**
     * Maps an {@link ApiKey} POJO to an immutable {@link ApiKeyInfo} DTO.
     */
    private static ApiKeyInfo toApiKeyInfo(ApiKey apiKey) {
        // Parse roles from comma-separated string
        Set<String> roles;
        if (apiKey.getRoles() != null && !apiKey.getRoles().isBlank()) {
            roles = Set.of(apiKey.getRoles().split(","));
        } else {
            roles = Set.of();
        }

        return new ApiKeyInfo(
            apiKey.getKeyPrefix(),
            apiKey.getTenantId(),
            apiKey.getSubject(),
            roles,
            apiKey.getStatus() != null ? apiKey.getStatus().toUpperCase() : null,
            apiKey.getCreatedAt() != null
                ? apiKey.getCreatedAt().toInstant(ZoneOffset.UTC)
                : null
        );
    }

    /**
     * Maps an {@link IdpConfig} POJO to an immutable {@link IdpConfigInfo} DTO.
     */
    private static IdpConfigInfo toIdpConfigInfo(IdpConfig idpConfig) {
        // Parse config_json back into a map (best-effort, simple key=value)
        Map<String, Object> config = new HashMap<>();
        if (idpConfig.getIssuerUrl() != null) {
            config.put("issuerUrl", idpConfig.getIssuerUrl());
        }
        if (idpConfig.getClientId() != null) {
            config.put("clientId", idpConfig.getClientId());
        }
        if (idpConfig.getJwksUri() != null) {
            config.put("jwksUri", idpConfig.getJwksUri());
        }
        // The config_json column contains the full serialized map, but for
        // now we reconstruct from the indexed columns. Full JSON parsing
        // will be added when a JSON library dependency is available.

        return new IdpConfigInfo(
            idpConfig.getTenantId(),
            idpConfig.getProvider(),
            Collections.unmodifiableMap(config),
            idpConfig.getCreatedAt() != null
                ? idpConfig.getCreatedAt().toInstant(ZoneOffset.UTC)
                : null
        );
    }

    // -----------------------------------------------------------------------
    // Internal — simple JSON serialization
    // -----------------------------------------------------------------------

    /**
     * Produces a minimal JSON object string from a map. Values are
     * quoted as strings. This avoids pulling in a JSON library dependency
     * for this single use case — a proper JSON library will be added in
     * Phase 5 (Spring Boot starter) where Jackson is already on the
     * classpath.
     *
     * @param map the map to serialize
     * @return a JSON object string (e.g. {@code {"key":"value"}})
     */
    private static String toSimpleJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            if (entry.getValue() == null) {
                sb.append("null");
            } else {
                sb
                    .append("\"")
                    .append(escapeJson(entry.getValue().toString()))
                    .append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Escapes special JSON characters in a string.
     */
    private static String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
