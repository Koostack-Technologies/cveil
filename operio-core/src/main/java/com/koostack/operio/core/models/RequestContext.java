package com.koostack.operio.core.models;

import java.util.concurrent.Callable;

/**
 * Scoped request metadata context using JDK 21's {@link ScopedValue} (preview).
 *
 * <p>Carries per-request metadata (actor identity, IP address, user agent)
 * through the call stack without polluting SPI method signatures. The auth
 * filter binds it alongside {@link TenantContext}:
 *
 * <pre>{@code
 * RequestContext.runWhere(
 *     new RequestContext.RequestMeta(identity.subject(), remoteAddr, userAgent),
 *     () -> {
 *         TenantContext.runWhere(identity.tenantId(), () -> {
 *             filterChain.doFilter(request, response);
 *         });
 *     }
 * );
 * }</pre>
 *
 * <p>When no request context is bound (e.g. programmatic calls outside HTTP),
 * the accessor methods return sensible defaults: {@code "system"} for actor
 * and {@code null} for IP address and user agent. This keeps the SPI
 * signatures stable and the metadata plumbing consistent with the existing
 * scoped context pattern.
 *
 * <h2>Why {@code ScopedValue} over method parameters</h2>
 * <ul>
 *   <li>The sync service SPI ({@code syncGrant(subject, scope, expiresAt)})
 *       has no parameter for actor, IP, or user agent.</li>
 *   <li>Adding parameters would break all existing implementations and
 *       callers.</li>
 *   <li>{@code ScopedValue} is the idiomatic JDK 21+ mechanism for
 *       request-scoped data — same approach as {@link TenantContext}.</li>
 *   <li>Immutable within a scope, automatically cleaned up on exit.</li>
 * </ul>
 *
 * @see TenantContext
 */
public final class RequestContext {

    /**
     * Immutable carrier for per-request metadata.
     *
     * @param actor     the identity performing the action (e.g. the admin
     *                  user or the subject themselves)
     * @param ipAddress the originating IP address, or {@code null}
     * @param userAgent the originating User-Agent header, or {@code null}
     */
    public record RequestMeta(
        String actor,
        String ipAddress,
        String userAgent
    ) {}

    private static final ScopedValue<RequestMeta> CURRENT =
        ScopedValue.newInstance();

    /** Default actor when no request context is bound. */
    private static final String DEFAULT_ACTOR = "system";

    private RequestContext() {
        // Utility class — no instantiation
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /**
     * Returns the current request metadata, or {@code null} if no request
     * scope is active (programmatic / non-HTTP call).
     *
     * @return the current request metadata, or {@code null}
     */
    public static RequestMeta get() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /**
     * Returns the actor from the current request context, or
     * {@code "system"} if no request scope is active.
     *
     * @return the actor identity — never {@code null}
     */
    public static String actor() {
        RequestMeta meta = get();
        if (meta == null || meta.actor() == null) {
            return DEFAULT_ACTOR;
        }
        return meta.actor();
    }

    /**
     * Returns the originating IP address from the current request context,
     * or {@code null} if no request scope is active.
     *
     * @return the IP address, or {@code null}
     */
    public static String ipAddress() {
        RequestMeta meta = get();
        return meta != null ? meta.ipAddress() : null;
    }

    /**
     * Returns the originating User-Agent header from the current request
     * context, or {@code null} if no request scope is active.
     *
     * @return the user agent string, or {@code null}
     */
    public static String userAgent() {
        RequestMeta meta = get();
        return meta != null ? meta.userAgent() : null;
    }

    /**
     * Whether a request context is currently active.
     *
     * @return {@code true} if request metadata is bound in the current scope
     */
    public static boolean isPresent() {
        return CURRENT.isBound();
    }

    // -----------------------------------------------------------------------
    // Scope binding
    // -----------------------------------------------------------------------

    /**
     * Execute a {@link Callable} within a request metadata scope.
     *
     * <p>The metadata is visible to all code on the current thread
     * (including virtual thread continuations) for the duration of the call
     * and is automatically cleared on exit.
     *
     * @param <R>  the return type
     * @param meta the request metadata to bind for the duration of {@code op}
     * @param op   the operation to execute within the request scope
     * @return the result of the operation
     * @throws Exception if the operation throws
     */
    public static <R> R callWhere(RequestMeta meta, Callable<? extends R> op)
        throws Exception {
        return ScopedValue.where(CURRENT, meta).call(op);
    }

    /**
     * Execute a {@link Runnable} within a request metadata scope.
     *
     * <p>Same scoping semantics as {@link #callWhere} — the metadata is
     * bound for the duration of the runnable and automatically cleared on
     * exit.
     *
     * @param meta the request metadata to bind for the duration of {@code op}
     * @param op   the operation to execute within the request scope
     */
    public static void runWhere(RequestMeta meta, Runnable op) {
        ScopedValue.where(CURRENT, meta).run(op);
    }

    // No set() or clear() — ScopedValue enforces structured concurrency.
    // The auth filter uses runWhere() / callWhere() to bind the metadata
    // for the duration of request processing.
}
