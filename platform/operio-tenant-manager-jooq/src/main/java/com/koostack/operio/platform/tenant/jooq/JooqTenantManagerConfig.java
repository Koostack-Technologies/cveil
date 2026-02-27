package com.koostack.operio.platform.tenant.jooq;

import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import javax.sql.DataSource;
import org.jooq.DSLContext;

/**
 * Configuration for {@link JooqTenantManagerService}.
 *
 * @param platformDsl               the platform {@link DSLContext}
 * @param dataSource                the {@link DataSource} for Flyway migrations
 * @param tenantRepository          repository for tenant queries
 * @param redisConfig               Redis connection config, or {@code null} to skip Redis
 * @param appName                   application name for Redis key prefix
 * @param consentMigrationLocation  Flyway migration location
 */
public record JooqTenantManagerConfig(
    DSLContext platformDsl,
    DataSource dataSource,
    TenantRepository tenantRepository,
    RedisPluginConfig redisConfig,
    String appName,
    String consentMigrationLocation
) {
    public JooqTenantManagerConfig {
        if (platformDsl == null) {
            throw new IllegalArgumentException("platformDsl must not be null");
        }
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
    }
}
