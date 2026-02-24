package com.koostack.operio.rest.context;

import com.koostack.operio.core.models.IdentityContext;

import jakarta.enterprise.context.RequestScoped;

/**
 * Request-scoped CDI bean that carries the authenticated
 * {@link IdentityContext} and HTTP request metadata between the
 * {@code ApiKeyAuthFilter} and the {@code TenantScopeInterceptor}.
 *
 * <h2>Why a holder bean instead of request properties?</h2>
 * <p>JAX-RS request properties ({@code ContainerRequestContext.setProperty()})
 * are only accessible within JAX-RS filters and resource methods — they are
 * <strong>not</strong> accessible from CDI interceptors. The
 * {@code TenantScopeInterceptor} is a CDI {@code @AroundInvoke} interceptor
 * that wraps resource method invocation inside {@code ScopedValue} bindings,
 * so it needs a CDI-injectable carrier for the authentication result.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@code ApiKeyAuthFilter} authenticates the request and calls
 *       {@link #set(IdentityContext, String, String)} to populate this
 *       holder.</li>
 *   <li>{@code TenantScopeInterceptor} reads the holder via
 *       {@link #identity()}, {@link #remoteAddress()}, and
 *       {@link #userAgent()} to bind {@code TenantContext} and
 *       {@code RequestContext} scoped values.</li>
 *   <li>The holder is automatically destroyed at the end of the CDI
 *       request scope (i.e. when the HTTP request completes).</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>This bean is request-scoped — each HTTP request gets its own instance.
 * With Quarkus virtual threads enabled, each request runs on a dedicated
 * virtual thread, so there is no concurrent access concern.
 *
 * @see com.koostack.operio.rest.filter.ApiKeyAuthFilter
 * @see com.koostack.operio.rest.interceptor.TenantScopeInterceptor
 */
@RequestScoped
public class OperioRequestHolder {

    private IdentityContext identity;
    private String remoteAddress;
    private String userAgent;

    /**
     * Populates this holder with the authenticated identity and request
     * metadata.
     *
     * <p>Called by {@code ApiKeyAuthFilter} after successful authentication.
     * Must be called at most once per request (the holder is immutable after
     * the first call — subsequent calls overwrite silently, but the contract
     * is single-write).
     *
     * @param identity      the authenticated identity context — must not be
     *                      {@code null}
     * @param remoteAddress the originating IP address (from
     *                      {@code X-Forwarded-For}, {@code X-Real-IP}, or
     *                      the direct connection), may be {@code null}
     * @param userAgent     the {@code User-Agent} header value, may be
     *                      {@code null}
     */
    public void set(
        IdentityContext identity,
        String remoteAddress,
        String userAgent
    ) {
        this.identity = identity;
        this.remoteAddress = remoteAddress;
        this.userAgent = userAgent;
    }

    /**
     * Returns the authenticated identity context, or {@code null} if the
     * request was not authenticated (e.g. public health-check endpoints
     * that bypass the auth filter).
     *
     * @return the identity context, or {@code null}
     */
    public IdentityContext identity() {
        return identity;
    }

    /**
     * Returns the originating IP address of the request, or {@code null}
     * if it could not be determined.
     *
     * <p>Resolution order (performed by the auth filter):
     * <ol>
     *   <li>{@code X-Forwarded-For} header (first entry — closest to
     *       client)</li>
     *   <li>{@code X-Real-IP} header</li>
     *   <li>Direct connection remote address (Vert.x
     *       {@code HttpServerRequest.remoteAddress()})</li>
     * </ol>
     *
     * @return the remote IP address, or {@code null}
     */
    public String remoteAddress() {
        return remoteAddress;
    }

    /**
     * Returns the {@code User-Agent} header value, or {@code null} if the
     * header was not present.
     *
     * @return the user agent string, or {@code null}
     */
    public String userAgent() {
        return userAgent;
    }

    /**
     * Whether this holder has been populated with an authenticated identity.
     *
     * <p>Returns {@code false} for unauthenticated requests (public
     * endpoints) and {@code true} after the auth filter has successfully
     * authenticated the bearer token or bootstrap key.
     *
     * @return {@code true} if an identity has been set
     */
    public boolean isAuthenticated() {
        return identity != null;
    }

    @Override
    public String toString() {
        if (identity == null) {
            return "OperioRequestHolder[unauthenticated]";
        }
        return "OperioRequestHolder[subject=" + identity.subject()
            + ", tenantId=" + identity.tenantId()
            + ", remoteAddress=" + remoteAddress
            + "]";
    }
}
