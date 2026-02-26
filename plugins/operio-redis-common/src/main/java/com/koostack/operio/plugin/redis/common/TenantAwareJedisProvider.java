package com.koostack.operio.plugin.redis.common;

import com.koostack.operio.core.models.TenantContext;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

/**
 * {@link JedisProvider} implementation that returns a per-tenant
 * {@link JedisPooled} connection pool, authenticated as the tenant's
 * Redis ACL user.
 *
 * <p>This is the core mechanism for enforcing <strong>server-side</strong>
 * Redis key isolation between tenants. Each tenant provisioned by
 * {@code TenantManager} has a dedicated Redis ACL user (e.g. {@code t_acme})
 * restricted to keys matching {@code {appName}:{tenantId}:*}. This provider
 * creates and caches a {@link JedisPooled} per tenant that authenticates
 * with that ACL user's credentials. Even if application-level key-prefix
 * logic has a bug, Redis itself will reject any operation on keys outside
 * the tenant's permitted pattern — true defense in depth.
 *
 * <h2>Connection Lifecycle</h2>
 * <ul>
 *   <li>Per-tenant pools are created lazily on first request and cached
 *       in a {@link ConcurrentHashMap} for the lifetime of the application.</li>
 *   <li>Each pool is a standard {@link JedisPooled} backed by an Apache
 *       Commons Pool — connections are borrowed/returned transparently.</li>
 *   <li>Call {@link #close()} at application shutdown to close all cached
 *       pools and release resources.</li>
 * </ul>
 *
 * <h2>Credential Resolution</h2>
 * <p>Tenant credentials (ACL username + plaintext password) are resolved
 * via a {@link TenantCredentialLookup} callback. The typical implementation
 * queries the platform {@code tenant} table for the {@code redis_acl_user}
 * and {@code redis_acl_password} columns. This lookup is called once per
 * tenant (on first access) — the result is cached along with the pool.
 *
 * <h2>Fallback for Admin / No-Tenant Context</h2>
 * <p>When no {@link TenantContext} is active (e.g. platform-level admin
 * operations, bootstrap key requests, health checks), this provider
 * returns the <strong>admin</strong> {@link JedisPooled} — the shared
 * connection that authenticates as the Redis {@code default} user. This
 * is intentional: admin operations need access to all keys for
 * provisioning, migration, and diagnostics. The admin pool is passed in
 * at construction time.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe. The tenant pool cache uses
 * {@link ConcurrentHashMap#computeIfAbsent} for atomic lazy creation.
 * The admin pool reference is immutable after construction.
 *
 * <h2>Memory Considerations</h2>
 * <p>Each cached pool consumes a small number of TCP connections to Redis
 * (default Jedis pool: 8 idle connections). For a platform with N tenants,
 * peak connection count is {@code N × poolSize + adminPoolSize}. If this
 * becomes a concern at scale, consider:
 * <ul>
 *   <li>Reducing per-tenant pool sizes via
 *       {@link redis.clients.jedis.JedisPoolConfig}</li>
 *   <li>Implementing LRU eviction of idle tenant pools</li>
 *   <li>Moving to a Redis Cluster proxy (Envoy, Twemproxy) that handles
 *       connection multiplexing</li>
 * </ul>
 *
 * @see JedisProvider
 * @see TenantCredentialLookup
 * @see TenantContext
 */
public class TenantAwareJedisProvider implements JedisProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(
        TenantAwareJedisProvider.class
    );

    /**
     * Callback for resolving a tenant's Redis ACL credentials from the
     * platform data store (typically the {@code tenant} table).
     *
     * <p>Implementations must be thread-safe. The lookup is invoked at most
     * once per tenant (result is cached). If a tenant is not found or has
     * no Redis ACL credentials, the lookup should return {@code null} —
     * the provider will throw an {@link IllegalStateException} to prevent
     * silent fallback to the admin connection.
     *
     * @see TenantAwareJedisProvider
     */
    @FunctionalInterface
    public interface TenantCredentialLookup {
        /**
         * Resolves Redis ACL credentials for the given tenant.
         *
         * @param tenantId the tenant identifier
         * @return the credentials, or {@code null} if the tenant has no
         *         Redis ACL configured
         */
        TenantRedisCredentials lookup(String tenantId);
    }

    /**
     * Redis ACL credentials for a specific tenant.
     *
     * @param aclUsername the Redis ACL username (e.g. {@code "t_acme"})
     * @param aclPassword the plaintext password for Redis AUTH
     */
    public record TenantRedisCredentials(
        String aclUsername,
        String aclPassword
    ) {
        public TenantRedisCredentials {
            if (aclUsername == null || aclUsername.isBlank()) {
                throw new IllegalArgumentException(
                    "Redis ACL username must not be null or blank"
                );
            }
            if (aclPassword == null || aclPassword.isBlank()) {
                throw new IllegalArgumentException(
                    "Redis ACL password must not be null or blank"
                );
            }
        }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final String host;
    private final int port;
    private final JedisPooled adminPool;
    private final TenantCredentialLookup credentialLookup;
    private final ConcurrentMap<String, JedisPooled> tenantPools;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Creates a new tenant-aware Jedis provider.
     *
     * @param host             the Redis server hostname
     * @param port             the Redis server port
     * @param adminPool        the admin-level {@link JedisPooled} (default user),
     *                         used when no tenant context is active
     * @param credentialLookup callback to resolve per-tenant ACL credentials
     * @throws NullPointerException if any argument is {@code null}
     */
    public TenantAwareJedisProvider(
        String host,
        int port,
        JedisPooled adminPool,
        TenantCredentialLookup credentialLookup
    ) {
        if (host == null || host.isBlank()) {
            throw new NullPointerException(
                "Redis host must not be null or blank"
            );
        }
        if (adminPool == null) {
            throw new NullPointerException(
                "Admin JedisPooled must not be null"
            );
        }
        if (credentialLookup == null) {
            throw new NullPointerException(
                "TenantCredentialLookup must not be null"
            );
        }
        this.host = host;
        this.port = port;
        this.adminPool = adminPool;
        this.credentialLookup = credentialLookup;
        this.tenantPools = new ConcurrentHashMap<>();
    }

    // -----------------------------------------------------------------------
    // JedisProvider
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link JedisPooled} for the current execution context.
     *
     * <ul>
     *   <li>If a {@link TenantContext} is active, returns a per-tenant pool
     *       authenticated as that tenant's Redis ACL user.</li>
     *   <li>If no tenant context is active, returns the admin pool.</li>
     * </ul>
     *
     * @return the appropriate {@link JedisPooled} — never {@code null}
     * @throws IllegalStateException if the tenant has no Redis ACL
     *                               credentials configured
     */
    @Override
    public JedisPooled get() {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            // No tenant scope — admin / platform-level operation
            return adminPool;
        }
        return tenantPools.computeIfAbsent(tenantId, this::createTenantPool);
    }

    // -----------------------------------------------------------------------
    // Pool management
    // -----------------------------------------------------------------------

    /**
     * Returns the number of currently cached tenant pools.
     *
     * <p>Primarily useful for monitoring and tests.
     *
     * @return the number of tenant pools in the cache
     */
    public int cachedPoolCount() {
        return tenantPools.size();
    }

    /**
     * Evicts and closes the cached pool for a specific tenant.
     *
     * <p>Called when a tenant is deleted or its Redis ACL credentials are
     * rotated. The next request for this tenant will trigger a fresh
     * credential lookup and pool creation.
     *
     * @param tenantId the tenant to evict
     */
    public void evict(String tenantId) {
        JedisPooled pool = tenantPools.remove(tenantId);
        if (pool != null) {
            try {
                pool.close();
                log.debug(
                    "Evicted and closed Redis pool for tenant '{}'",
                    tenantId
                );
            } catch (Exception e) {
                log.warn(
                    "Error closing evicted Redis pool for tenant '{}'",
                    tenantId,
                    e
                );
            }
        }
    }

    /**
     * Closes all cached tenant pools and the admin pool.
     *
     * <p>Call this at application shutdown to release all Redis connections.
     */
    @Override
    public void close() {
        // Close all tenant pools
        for (var entry : tenantPools.entrySet()) {
            try {
                entry.getValue().close();
                log.debug(
                    "Closed Redis pool for tenant '{}'",
                    entry.getKey()
                );
            } catch (Exception e) {
                log.warn(
                    "Error closing Redis pool for tenant '{}'",
                    entry.getKey(),
                    e
                );
            }
        }
        tenantPools.clear();

        // Close admin pool
        try {
            adminPool.close();
            log.debug("Closed admin Redis pool");
        } catch (Exception e) {
            log.warn("Error closing admin Redis pool", e);
        }

        log.info(
            "TenantAwareJedisProvider: all Redis pools closed"
        );
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    /**
     * Creates a new {@link JedisPooled} for the given tenant, authenticated
     * with the tenant's Redis ACL credentials.
     *
     * <p>This method is called at most once per tenant via
     * {@link ConcurrentHashMap#computeIfAbsent}.
     *
     * @param tenantId the tenant identifier
     * @return a connected, authenticated {@link JedisPooled}
     * @throws IllegalStateException if credentials cannot be resolved
     */
    private JedisPooled createTenantPool(String tenantId) {
        TenantRedisCredentials credentials = credentialLookup.lookup(tenantId);

        if (credentials == null) {
            throw new IllegalStateException(
                "No Redis ACL credentials found for tenant '" + tenantId +
                "'. The tenant may have been provisioned before per-tenant " +
                "Redis ACL isolation was enabled (redis_acl_password column " +
                "is NULL). Run TenantManager.reprovisionRedisAcl() or " +
                "manually set the credentials for this tenant."
            );
        }

        log.info(
            "Creating per-tenant Redis pool for tenant '{}' " +
            "(aclUser='{}')",
            tenantId,
            credentials.aclUsername()
        );

        // JedisPooled(host, port, user, password) — authenticates as the
        // tenant's ACL user, which is restricted to keys matching
        // {appName}:{tenantId}:* by the Redis ACL rule set during
        // TenantManager.provisionTenant().
        return new JedisPooled(
            host,
            port,
            credentials.aclUsername(),
            credentials.aclPassword()
        );
    }
}
