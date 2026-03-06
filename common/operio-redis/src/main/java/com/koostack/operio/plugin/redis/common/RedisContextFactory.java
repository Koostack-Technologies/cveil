package com.koostack.operio.plugin.redis.common;

import redis.clients.jedis.JedisPooled;

/**
 * High-level factory that creates a fully initialized {@link JedisPooled} client or {@link
 * JedisProvider} for a Redis-backed plugin.
 *
 * <p>Handles connection setup in one call, branching on whether authentication is required and
 * whether per-tenant ACL isolation is desired.
 *
 * <h2>Single-Tenant / Shared Connection</h2>
 *
 * <p>Use {@link #createClient(RedisPluginConfig)} to create a single shared {@link JedisPooled}
 * that authenticates as the Redis {@code default} user. Suitable for single-tenant deployments,
 * embedded mode, and tests.
 *
 * <h2>Multi-Tenant / Per-Tenant ACL Isolation</h2>
 *
 * <p>Use {@link #createProvider(RedisPluginConfig,
 * TenantAwareJedisProvider.TenantCredentialLookup)} to create a {@link TenantAwareJedisProvider}
 * that returns per-tenant {@link JedisPooled} connections authenticated as each tenant's dedicated
 * Redis ACL user. This provides <strong>server-side</strong> key isolation enforced by Redis itself
 * — not just application-level key-prefix isolation.
 */
public final class RedisContextFactory {

    private RedisContextFactory() {
        // Utility class — not meant to be instantiated
    }

    /**
     * Creates a ready-to-use {@link JedisPooled} client from the given plugin config.
     *
     * <p>If the config provides a non-null, non-blank password, the client is created with
     * authentication (as the {@code default} user). Otherwise, it connects without credentials.
     *
     * <p><strong>Note:</strong> In multi-tenant deployments, prefer {@link
     * #createProvider(RedisPluginConfig, TenantAwareJedisProvider.TenantCredentialLookup)} for
     * per-tenant ACL isolation.
     *
     * @param config the plugin's Redis configuration (host, port, password)
     * @return a configured {@link JedisPooled} instance
     */
    public static JedisPooled createClient(RedisPluginConfig config) {
        return RedisClientFactory.createClient(
                config.host(), config.port(), config.password(), config.tlsConfig());
    }

    /**
     * Creates a {@link JedisProvider} that wraps a single shared {@link JedisPooled} client from
     * the given plugin config.
     *
     * <p>This is equivalent to {@code JedisProvider.shared(createClient(config))} — all tenants
     * share the same connection (application-level key-prefix isolation only, no Redis ACL
     * enforcement).
     *
     * @param config the plugin's Redis configuration (host, port, password)
     * @return a shared {@link JedisProvider}
     */
    public static JedisProvider createSharedProvider(RedisPluginConfig config) {
        return JedisProvider.shared(createClient(config));
    }

    /**
     * Creates a {@link TenantAwareJedisProvider} that returns per-tenant {@link JedisPooled}
     * connections authenticated as each tenant's dedicated Redis ACL user.
     *
     * <p>This is the recommended factory method for multi-tenant deployments. Each tenant's Redis
     * operations will authenticate as that tenant's ACL user (e.g. {@code t_acme}), which Redis
     * restricts to keys matching the tenant's permitted pattern ({@code {appName}:{tenantId}:*}).
     * Even if application-level key-prefix logic has a bug, Redis itself will reject operations on
     * keys outside the tenant's pattern — true defense in depth.
     *
     * <p>When no tenant context is active (e.g. platform-level admin operations), the provider
     * falls back to the admin pool (default user) created from the given config.
     *
     * @param config the Redis connection config (host, port, admin password)
     * @param credentialLookup callback to resolve per-tenant ACL credentials from the platform data
     *     store
     * @return a tenant-aware {@link JedisProvider}
     */
    public static TenantAwareJedisProvider createProvider(
            RedisPluginConfig config,
            TenantAwareJedisProvider.TenantCredentialLookup credentialLookup) {
        JedisPooled adminPool = createClient(config);
        return new TenantAwareJedisProvider(
                config.host(), config.port(), adminPool, credentialLookup, config.tlsConfig());
    }
}
