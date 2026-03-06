package com.koostack.operio.platform.tenant.jooq;

import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import javax.sql.DataSource;
import org.jooq.DSLContext;

/**
 * Configuration for {@link JooqTenantManagerService}.
 *
 * @param platformDsl                the platform {@link DSLContext}
 * @param dataSource                 the {@link DataSource} for Flyway migrations
 * @param tenantRepository           repository for tenant queries
 * @param redisConfig                Redis connection config, or {@code null} to skip Redis
 * @param appName                    application name for Redis key prefix
 * @param consentMigrationLocation   Flyway migration location for consent schema
 * @param eventMigrationLocation     Flyway migration location for per-tenant event store schema,
 *                                   or {@code null} to skip event schema provisioning
 * @param metricsMigrationLocation   Flyway migration location for per-tenant metrics schema,
 *                                   or {@code null} to skip metrics provisioning
 */
public record JooqTenantManagerConfig(
    DSLContext platformDsl,
    DataSource dataSource,
    TenantRepository tenantRepository,
    RedisPluginConfig redisConfig,
    String appName,
    String consentMigrationLocation,
    String eventMigrationLocation,
    String metricsMigrationLocation
) {
    public JooqTenantManagerConfig {
        if (platformDsl == null) {
            throw new IllegalArgumentException("platformDsl must not be null");
        }
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
    }

    /**
     * Backward-compatible constructor without event or metrics migration locations.
     */
    public JooqTenantManagerConfig(
        DSLContext platformDsl,
        DataSource dataSource,
        TenantRepository tenantRepository,
        RedisPluginConfig redisConfig,
        String appName,
        String consentMigrationLocation
    ) {
        this(platformDsl, dataSource, tenantRepository, redisConfig,
             appName, consentMigrationLocation, null, null);
    }
}
