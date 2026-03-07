package com.koostack.operio.platform.tenant.jooq;

import com.koostack.operio.plugin.platform.keycloak.KeycloakRealmProvisioner;
import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import javax.sql.DataSource;
import org.jooq.DSLContext;

/**
 * Configuration for {@link JooqTenantManagerService}.
 *
 * @param platformDsl the platform {@link DSLContext} (for platform_core queries)
 * @param tenantDataSource the {@link DataSource} targeting the tenant database — used for CREATE
 *     SCHEMA and Flyway migrations on per-tenant schemas ({@code t_{tenantId}}, etc.)
 * @param tenantRepository repository for tenant queries
 * @param redisConfig Redis connection config, or {@code null} to skip Redis
 * @param appName application name for Redis key prefix
 * @param consentMigrationLocation Flyway migration location for consent schema
 * @param eventMigrationLocation Flyway migration location for per-tenant event store schema, or
 *     {@code null} to skip event schema provisioning
 * @param metricsMigrationLocation Flyway migration location for per-tenant metrics schema, or
 *     {@code null} to skip metrics provisioning
 * @param keycloakProvisioner Keycloak realm provisioner, or {@code null} to skip Keycloak realm
 *     provisioning
 * @param tenantAdminEmail email address for the tenant root admin user, or {@code null} to skip
 *     root user creation
 */
public record JooqTenantManagerConfig(
        DSLContext platformDsl,
        DataSource tenantDataSource,
        TenantRepository tenantRepository,
        RedisPluginConfig redisConfig,
        String appName,
        String consentMigrationLocation,
        String eventMigrationLocation,
        String metricsMigrationLocation,
        KeycloakRealmProvisioner keycloakProvisioner,
        String tenantAdminEmail) {
    public JooqTenantManagerConfig {
        if (platformDsl == null) {
            throw new IllegalArgumentException("platformDsl must not be null");
        }
        if (tenantDataSource == null) {
            throw new IllegalArgumentException("tenantDataSource must not be null");
        }
    }

    /** Backward-compatible constructor without Keycloak provisioner. */
    public JooqTenantManagerConfig(
            DSLContext platformDsl,
            DataSource tenantDataSource,
            TenantRepository tenantRepository,
            RedisPluginConfig redisConfig,
            String appName,
            String consentMigrationLocation,
            String eventMigrationLocation,
            String metricsMigrationLocation) {
        this(
                platformDsl,
                tenantDataSource,
                tenantRepository,
                redisConfig,
                appName,
                consentMigrationLocation,
                eventMigrationLocation,
                metricsMigrationLocation,
                null,
                null);
    }

    /** Backward-compatible constructor without event or metrics migration locations. */
    public JooqTenantManagerConfig(
            DSLContext platformDsl,
            DataSource tenantDataSource,
            TenantRepository tenantRepository,
            RedisPluginConfig redisConfig,
            String appName,
            String consentMigrationLocation) {
        this(
                platformDsl,
                tenantDataSource,
                tenantRepository,
                redisConfig,
                appName,
                consentMigrationLocation,
                null,
                null,
                null,
                null);
    }
}
