package com.koostack.operio.tenant.event.jooq;

import com.koostack.operio.plugin.jooq.common.JooqPluginConfig;
import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider;

import java.util.List;
import java.util.function.Supplier;

/**
 * Configuration record for the {@code operio-tenant-event-jooq} module.
 *
 * @param connectionString       the JDBC URL for the event store database
 * @param dialect                the jOOQ {@link org.jooq.SQLDialect} name
 * @param username               the database username, or {@code null}
 * @param password               the database password, or {@code null}
 * @param redis                  Redis connection config, or {@code null}
 * @param tenantCredentialLookup callback to resolve per-tenant Redis ACL
 *                               credentials, or {@code null}
 * @param tenantDiscovery        supplier that returns active tenant IDs
 */
public record TenantEventJooqConfig(
    String connectionString,
    String dialect,
    String username,
    String password,
    RedisPluginConfig redis,
    TenantAwareJedisProvider.TenantCredentialLookup tenantCredentialLookup,
    Supplier<List<String>> tenantDiscovery
) implements JooqPluginConfig {

    public TenantEventJooqConfig(String connectionString, String dialect) {
        this(connectionString, dialect, null, null, null, null, null);
    }

    public TenantEventJooqConfig(
        String connectionString,
        String dialect,
        RedisPluginConfig redis
    ) {
        this(connectionString, dialect, null, null, redis, null, null);
    }

    public TenantEventJooqConfig(
        String connectionString,
        String dialect,
        String username,
        String password,
        RedisPluginConfig redis
    ) {
        this(connectionString, dialect, username, password, redis, null, null);
    }

    public TenantEventJooqConfig(
        String connectionString,
        String dialect,
        String username,
        String password,
        RedisPluginConfig redis,
        TenantAwareJedisProvider.TenantCredentialLookup tenantCredentialLookup
    ) {
        this(connectionString, dialect, username, password, redis, tenantCredentialLookup, null);
    }
}
