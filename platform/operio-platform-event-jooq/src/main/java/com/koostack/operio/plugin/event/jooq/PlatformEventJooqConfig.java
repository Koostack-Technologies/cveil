package com.koostack.operio.plugin.event.jooq;

import com.koostack.operio.plugin.jooq.common.JooqPluginConfig;
import com.koostack.operio.plugin.redis.common.RedisPluginConfig;
import com.koostack.operio.plugin.redis.common.TenantAwareJedisProvider;

/**
 * Configuration record for the {@code operio-platform-event-jooq} module.
 *
 * <p>Extends the standard {@link JooqPluginConfig} with an optional {@link RedisPluginConfig} for
 * event publish/subscribe notifications. When Redis is configured, published events are also pushed
 * to a Redis Stream for real-time fanout to listeners.
 *
 * <p>When a {@link TenantAwareJedisProvider.TenantCredentialLookup} is provided alongside the Redis
 * config, the event publisher will use per-tenant Redis ACL isolation via {@link
 * TenantAwareJedisProvider}. When omitted, a shared (non-tenant-aware) connection is used.
 *
 * @param connectionString the JDBC URL for the event store database
 * @param dialect the jOOQ {@link org.jooq.SQLDialect} name (e.g. {@code "POSTGRES"}, {@code "H2"})
 * @param username the database username, or {@code null} to use the driver default
 * @param password the database password, or {@code null} to use the driver default
 * @param redis Redis connection config for event pub/sub, or {@code null} to disable Redis
 *     notifications
 * @param tenantCredentialLookup callback to resolve per-tenant Redis ACL credentials, or {@code
 *     null} to use a shared connection
 * @param shardCount number of shard streams for tenant event distribution (default 8)
 */
public record PlatformEventJooqConfig(
        String connectionString,
        String dialect,
        String username,
        String password,
        RedisPluginConfig redis,
        TenantAwareJedisProvider.TenantCredentialLookup tenantCredentialLookup,
        int shardCount)
        implements JooqPluginConfig {

    public PlatformEventJooqConfig(String connectionString, String dialect) {
        this(
                connectionString,
                dialect,
                null,
                null,
                null,
                null,
                RedisEventPublisher.DEFAULT_SHARD_COUNT);
    }

    public PlatformEventJooqConfig(
            String connectionString, String dialect, RedisPluginConfig redis) {
        this(
                connectionString,
                dialect,
                null,
                null,
                redis,
                null,
                RedisEventPublisher.DEFAULT_SHARD_COUNT);
    }

    public PlatformEventJooqConfig(
            String connectionString,
            String dialect,
            String username,
            String password,
            RedisPluginConfig redis) {
        this(
                connectionString,
                dialect,
                username,
                password,
                redis,
                null,
                RedisEventPublisher.DEFAULT_SHARD_COUNT);
    }

    public PlatformEventJooqConfig(
            String connectionString,
            String dialect,
            String username,
            String password,
            RedisPluginConfig redis,
            TenantAwareJedisProvider.TenantCredentialLookup tenantCredentialLookup) {
        this(
                connectionString,
                dialect,
                username,
                password,
                redis,
                tenantCredentialLookup,
                RedisEventPublisher.DEFAULT_SHARD_COUNT);
    }
}
