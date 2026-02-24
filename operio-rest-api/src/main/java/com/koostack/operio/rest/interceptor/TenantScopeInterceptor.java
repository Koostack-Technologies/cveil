package com.koostack.operio.rest.interceptor;

import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.models.RequestContext;
import com.koostack.operio.core.models.RequestContext.RequestMeta;
import com.koostack.operio.core.models.TenantContext;
import com.koostack.operio.rest.context.OperioRequestHolder;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI interceptor that binds {@link TenantContext} and {@link RequestContext}
 * {@link ScopedValue ScopedValues} around JAX-RS resource method invocations.
 *
 * <p>This interceptor is the bridge between the authentication layer
 * ({@link com.koostack.operio.rest.filter.ApiKeyAuthFilter ApiKeyAuthFilter})
 * and the Operio SPI layer. The auth filter authenticates the request and
 * stores the resolved {@link IdentityContext} plus HTTP metadata in the
 * {@link OperioRequestHolder} (a {@code @RequestScoped} CDI bean). This
 * interceptor reads that holder and wraps the resource method invocation
 * inside nested {@code ScopedValue} bindings:
 *
 * <pre>{@code
 * TenantContext.callWhere(identity.tenantId(), () ->
 *     RequestContext.callWhere(
 *         new RequestMeta(identity.subject(), remoteAddr, userAgent),
 *         () -> ctx.proceed()
 *     )
 * )
 * }</pre>
 *
 * <p>This ensures that <strong>all</strong> Operio SPI calls made during
 * request processing — consent evaluation, admin operations, sync, audit
 * logging — automatically see the correct tenant and request metadata via
 * {@code TenantContext.get()} and {@code RequestContext.actor()} without
 * polluting method signatures.
 *
 * <h2>Why a CDI Interceptor (not a JAX-RS Filter)?</h2>
 * <p>JAX-RS {@code ContainerRequestFilter} and {@code ContainerResponseFilter}
 * run <em>before</em> and <em>after</em> the resource method respectively —
 * they cannot <em>wrap</em> the invocation. {@code ScopedValue} requires
 * structured scoping ({@code callWhere} / {@code runWhere}), so the binding
 * must structurally enclose the resource method call. A CDI
 * {@code @AroundInvoke} interceptor provides exactly this wrapping semantic.
 *
 * <h2>Activation</h2>
 * <p>Apply the {@link TenantScoped @TenantScoped} interceptor binding
 * annotation on JAX-RS resource classes (or individual methods) that need
 * tenant and request context:
 *
 * <pre>{@code
 * @Path("/api/v1/consent")
 * @TenantScoped
 * public class ConsentResource {
 *     // TenantContext.get() and RequestContext.actor() are available here
 * }
 * }</pre>
 *
 * <h2>Null Tenant Handling</h2>
 * <p>When the authenticated identity has no {@code tenantId} (e.g. the
 * bootstrap super-admin), {@code TenantContext} is <strong>not</strong>
 * bound — {@code TenantContext.get()} will return {@code null} inside the
 * resource method. {@code RequestContext} is always bound when an identity
 * is present, since actor metadata is always available.
 *
 * <h2>Unauthenticated Requests</h2>
 * <p>If the {@link OperioRequestHolder} contains no identity (request was
 * not authenticated — e.g. the filter skipped it for a public path), the
 * interceptor proceeds without binding any scoped values. This is a
 * defensive guard; in practice, authenticated resource methods should
 * always have an identity set by the auth filter.
 *
 * <h2>Priority</h2>
 * <p>Runs at {@code PLATFORM_BEFORE + 10} — after CDI platform
 * interceptors (transactions, security) but before application-level
 * interceptors. This ensures the scoped values are available to any
 * downstream interceptors and the resource method itself.
 *
 * @see TenantScoped
 * @see OperioRequestHolder
 * @see TenantContext
 * @see RequestContext
 * @see com.koostack.operio.rest.filter.ApiKeyAuthFilter
 */
@Interceptor
@TenantScoped
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 10)
public class TenantScopeInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(
        TenantScopeInterceptor.class
    );

    // -----------------------------------------------------------------------
    // Injected dependencies
    // -----------------------------------------------------------------------

    /**
     * Request-scoped holder populated by
     * {@link com.koostack.operio.rest.filter.ApiKeyAuthFilter} with the
     * authenticated {@link IdentityContext} and HTTP request metadata
     * (remote address, user agent).
     */
    @Inject
    OperioRequestHolder requestHolder;

    // -----------------------------------------------------------------------
    // Interceptor logic
    // -----------------------------------------------------------------------

    /**
     * Wraps the intercepted resource method invocation in
     * {@link TenantContext} and {@link RequestContext} scoped-value bindings.
     *
     * <p>Flow:
     * <ol>
     *   <li>Read the {@link IdentityContext} from the
     *       {@link OperioRequestHolder}.</li>
     *   <li>If no identity is present (unauthenticated / public path),
     *       proceed without binding — defensive no-op.</li>
     *   <li>Build a {@link RequestMeta} from the identity's subject and the
     *       holder's remote address and user agent.</li>
     *   <li>If the identity has a non-null {@code tenantId}, nest the
     *       invocation inside both {@code TenantContext.callWhere()} and
     *       {@code RequestContext.callWhere()}.</li>
     *   <li>If the identity has no tenant (e.g. bootstrap super-admin),
     *       bind only {@code RequestContext} — tenant-scoped SPI calls
     *       will see {@code TenantContext.get() == null} and behave
     *       accordingly (single-tenant / admin mode).</li>
     * </ol>
     *
     * @param invocation the CDI invocation context
     * @return the result of the intercepted method
     * @throws Exception if the intercepted method (or scope binding) throws
     */
    @AroundInvoke
    Object wrapInScopedContext(InvocationContext invocation) throws Exception {
        IdentityContext identity = requestHolder.identity();

        // Guard: no identity means the request was not authenticated.
        // This shouldn't happen on @TenantScoped resources (they should be
        // behind the auth filter), but we handle it gracefully.
        if (identity == null) {
            LOG.warn(
                "TenantScopeInterceptor: no IdentityContext found in " +
                    "OperioRequestHolder — proceeding without scoped context. " +
                    "Is the ApiKeyAuthFilter running before this resource?"
            );
            return invocation.proceed();
        }

        String tenantId = identity.tenantId();
        RequestMeta meta = new RequestMeta(
            identity.subject(),
            requestHolder.remoteAddress(),
            requestHolder.userAgent()
        );

        if (LOG.isTraceEnabled()) {
            LOG.trace(
                "TenantScopeInterceptor: binding scoped context " +
                    "[tenant={}, subject={}, ip={}, method={}#{}]",
                tenantId != null ? tenantId : "<none>",
                identity.subject(),
                requestHolder.remoteAddress(),
                invocation.getTarget().getClass().getSimpleName(),
                invocation.getMethod().getName()
            );
        }

        // Bind TenantContext + RequestContext around the resource invocation.
        //
        // ScopedValue.where() does not accept null values, so we only bind
        // TenantContext when a tenantId is present. RequestContext is always
        // bound since the actor (subject) is always available after
        // authentication.
        if (tenantId != null) {
            return TenantContext.callWhere(tenantId, () ->
                RequestContext.callWhere(meta, invocation::proceed)
            );
        } else {
            // No tenant scope (e.g. bootstrap super-admin operating at
            // platform level). Only bind request metadata.
            return RequestContext.callWhere(meta, invocation::proceed);
        }
    }
}
