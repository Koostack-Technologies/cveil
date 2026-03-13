package com.koostack.operio.plugin.redis.common;

import com.koostack.operio.core.models.TenantContext;
import redis.clients.jedis.JedisPooled;

/**
 * Functional interface that abstracts the resolution of a {@link JedisPooled} client for the
 * current execution context.
 *
 * <p>This is the key abstraction that enables per-tenant Redis ACL isolation. Instead of
 * repositories holding a direct reference to a single shared {@link JedisPooled} instance (which
 * authenticates as the Redis {@code default} user with access to <em>all</em> keys), they hold a
 * {@code JedisProvider} that resolves the correct connection for the current tenant at call time.
 *
 * <h2>Implementations</h2>
 *
 * <ul>
 *   <li><strong>Shared (legacy / embedded)</strong> — always returns the same {@link JedisPooled}
 *       instance. Use {@link #shared(JedisPooled)} to create one. Suitable for single-tenant
 *       deployments or when Redis ACLs are not configured.
 *   <li><strong>Tenant-aware</strong> — reads the current tenant from {@link TenantContext}, looks
 *       up the tenant's Redis ACL credentials, and returns a per-tenant {@link JedisPooled}
 *       authenticated as that tenant's ACL user. The per-tenant ACL user is restricted to the
 *       tenant's key pattern ({@code {appName}:{tenantId}:*}), providing
 *       <strong>server-side</strong> key isolation enforced by Redis itself — not just
 *       application-level key-prefix isolation.
 * </ul>
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Implementations <strong>must</strong> be thread-safe — this provider is called concurrently
 *       from request threads.
 *   <li>The returned {@link JedisPooled} instance <strong>must</strong> be reusable across calls
 *       (i.e. backed by a connection pool). Callers do <strong>not</strong> close the returned
 *       instance.
 *   <li>If the current tenant cannot be resolved or credentials are missing, implementations should
 *       throw {@link IllegalStateException} with a descriptive message rather than silently falling
 *       back to an admin connection.
 * </ul>
 *
 * <h2>Why not just {@code Supplier<JedisPooled>}?</h2>
 *
 * <p>A dedicated interface provides:
 *
 * <ol>
 *   <li>Self-documenting type in constructor/field signatures
 *   <li>A place for the {@link #shared(JedisPooled)} factory method
 *   <li>Javadoc explaining the contract and security implications
 *   <li>A seam for future enhancements (e.g. {@code close()} for pool shutdown, health checks)
 * </ol>
 *
 * @see TenantAwareJedisProvider
 */
@FunctionalInterface
public interface JedisProvider {

    /**
     * Returns the {@link JedisPooled} client appropriate for the current execution context (tenant
     * scope, admin scope, etc.).
     *
     * <p>The returned instance is pooled and <strong>must not</strong> be closed by the caller.
     *
     * @return a connected, authenticated {@link JedisPooled} — never {@code null}
     * @throws IllegalStateException if credentials cannot be resolved for the current context
     */
    JedisPooled get();

    /**
     * Creates a {@code JedisProvider} that always returns the same shared {@link JedisPooled}
     * instance, regardless of tenant context.
     *
     * <p>This is the backward-compatible default for single-tenant deployments, embedded mode, and
     * tests. The shared instance typically authenticates as the Redis {@code default} user
     * (admin-level access).
     *
     * <p><strong>Security note:</strong> In multi-tenant production deployments, prefer {@link
     * TenantAwareJedisProvider} which enforces Redis-level ACL isolation per tenant.
     *
     * @param jedis the shared client instance — must not be {@code null}
     * @return a provider that always returns {@code jedis}
     * @throws NullPointerException if {@code jedis} is {@code null}
     */
    static JedisProvider shared(JedisPooled jedis) {
        if (jedis == null) {
            throw new NullPointerException("Shared JedisPooled instance must not be null");
        }
        return () -> jedis;
    }
}
