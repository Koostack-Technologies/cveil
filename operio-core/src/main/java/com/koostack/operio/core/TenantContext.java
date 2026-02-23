package com.koostack.operio.core;

import com.koostack.operio.core.exception.OperioConfigurationException;

/**
 * Scoped tenant context using JDK 25's {@link ScopedValue}.
 *
 * <p>{@code ScopedValue} is the modern replacement for {@code ThreadLocal} in
 * request-scoped contexts. It is designed for virtual threads (which Spring
 * Boot 4.0 enables by default) and eliminates the {@code set/clear} foot-gun
 * pattern. Values are immutable within a scope and automatically cleaned up
 * when the scope exits — no risk of leaking tenant context across requests.
 *
 * <h2>Usage in auth filter</h2>
 * <pre>{@code
 * // In ApiKeyAuthenticationFilter.doFilterInternal():
 * IdentityContext identity = tokenVerifier.verifyToken(bearerToken);
 * // ... set SecurityContextHolder ...
 * TenantContext.runWhere(identity.tenantId(), () -> {
 *     filterChain.doFilter(request, response);
 * });
 * // Tenant context is automatically cleared when runWhere() returns.
 * }</pre>
 *
 * <h2>Usage in data layer</h2>
 * <pre>{@code
 * // In TenantAwareExecuteListener:
 * String tenant = TenantContext.get();
 * if (tenant != null) {
 *     ctx.connectionProvider().acquire().createStatement()
 *         .execute("SET search_path TO t_" + tenant + ", public");
 * }
 * }</pre>
 *
 * <h2>Why {@code ScopedValue} over {@code ThreadLocal}</h2>
 * <ul>
 *   <li>Java 25 finalizes {@code ScopedValue} — the intended primitive for
 *       request-scoped data.</li>
 *   <li>Spring Boot 4.0 enables virtual threads by default —
 *       {@code ThreadLocal} on virtual threads carries per-carrier-thread
 *       overhead and risks subtle leaking across continuations.</li>
 *   <li>{@code ScopedValue} enforces structured lifetime (no {@code set}
 *       without {@code clear} bugs).</li>
 *   <li>Immutable within a scope — cannot be accidentally overwritten by
 *       nested code.</li>
 *   <li>Zero cleanup burden — the auth filter's {@code runWhere} scope
 *       handles everything.</li>
 * </ul>
 *
 * <p>The SPIs don't change signatures; the data layer reads tenant from
 * {@link #get()} or {@link #require()}.
 */
public final class TenantContext {

    private static final ScopedValue<String> CURRENT =
        ScopedValue.newInstance();

    private TenantContext() {
        // Utility class — no instantiation
    }

    /**
     * Returns the current tenant ID, or {@code null} if no tenant scope is
     * active (single-tenant / default mode).
     *
     * @return the current tenant ID, or {@code null}
     */
    public static String get() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /**
     * Returns the current tenant ID, throwing if no tenant scope is active.
     *
     * <p>Use in contexts where a tenant is always expected (e.g. inside the
     * auth filter scope).
     *
     * @return the current tenant ID — never {@code null}
     * @throws OperioConfigurationException if no tenant scope is active
     */
    public static String require() {
        return CURRENT.orElseThrow(() ->
            new OperioConfigurationException(
                "No tenant context — request not authenticated?"
            )
        );
    }

    /**
     * Execute a {@link ScopedValue.CallableOp} within a tenant scope.
     *
     * <p>The tenant ID is visible to all code on the current thread
     * (including virtual thread continuations) for the duration of the call
     * and is automatically cleared on exit.
     *
     * @param <R>      the return type
     * @param <X>      the exception type the operation may throw
     * @param tenantId the tenant ID to bind for the duration of {@code op}
     * @param op       the operation to execute within the tenant scope
     * @return the result of the operation
     * @throws X if the operation throws
     */
    public static <R, X extends Throwable> R callWhere(
        String tenantId,
        ScopedValue.CallableOp<? extends R, X> op
    ) throws X {
        return ScopedValue.where(CURRENT, tenantId).call(op);
    }

    /**
     * Execute a {@link Runnable} within a tenant scope.
     *
     * <p>Same scoping semantics as {@link #callWhere} — the tenant ID is
     * bound for the duration of the runnable and automatically cleared on
     * exit.
     *
     * @param tenantId the tenant ID to bind for the duration of {@code op}
     * @param op       the operation to execute within the tenant scope
     */
    public static void runWhere(String tenantId, Runnable op) {
        ScopedValue.where(CURRENT, tenantId).run(op);
    }

    /**
     * Whether a tenant scope is currently active.
     *
     * @return {@code true} if a tenant ID is bound in the current scope
     */
    public static boolean isPresent() {
        return CURRENT.isBound();
    }

    // No set() or clear() — ScopedValue enforces structured concurrency.
    // The auth filter uses runWhere() / callWhere() to bind the tenant for
    // the duration of request processing.
}
