package com.koostack.operio.rest.interceptor;

import com.koostack.operio.core.models.RequestContext;
import com.koostack.operio.core.models.TenantContext;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI interceptor binding that activates {@link TenantScopeInterceptor} on the annotated class or
 * method.
 *
 * <p>When applied, every method invocation on the target is wrapped inside nested {@code
 * ScopedValue} bindings so that {@link TenantContext TenantContext} and {@link RequestContext
 * RequestContext} are available throughout the entire call stack — including all Operio SPI
 * implementations (consent decision, admin, sync, audit, masking, etc.).
 *
 * <h2>Usage</h2>
 *
 * <p>Apply to JAX-RS resource classes that require tenant-scoped Operio operations:
 *
 * <pre>{@code
 * @Path("/api/v1/subjects")
 * @TenantScoped
 * public class ConsentResource {
 *
 *     @Inject Operio operio;
 *
 *     @POST @Path("/grant")
 *     public Response grant(GrantRequest request) {
 *         // TenantContext.get() and RequestContext.actor() are available here
 *         operio.grantConsent(request.subject(), request.scope(), ...);
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p>The annotation can also be applied at the method level for fine-grained control, but
 * class-level application is recommended for resource classes since virtually all endpoints operate
 * within a tenant context.
 *
 * <h2>How It Works</h2>
 *
 * <ol>
 *   <li>{@link com.koostack.operio.rest.filter.ApiKeyAuthFilter ApiKeyAuthFilter} authenticates the
 *       request and populates {@link com.koostack.operio.rest.context.OperioRequestHolder
 *       OperioRequestHolder} with the {@code IdentityContext}, remote address, and user agent.
 *   <li>{@link TenantScopeInterceptor} fires on every method invocation of the annotated class,
 *       reads the holder, and wraps {@code ctx.proceed()} inside:
 *       <pre>{@code
 * TenantContext.callWhere(tenantId, () ->
 *     RequestContext.callWhere(meta, () ->
 *         ctx.proceed()
 *     )
 * )
 *
 * }</pre>
 *   <li>On scope exit, both {@code ScopedValue} bindings are automatically cleared — no risk of
 *       tenant context leaking across requests.
 * </ol>
 *
 * <h2>Prerequisites</h2>
 *
 * <ul>
 *   <li>Quarkus virtual threads must be enabled ({@code quarkus.virtual-threads.enabled=true}) so
 *       that {@code ScopedValue} works correctly with structured concurrency.
 *   <li>{@link com.koostack.operio.rest.filter.ApiKeyAuthFilter ApiKeyAuthFilter} must run before
 *       this interceptor (it does — JAX-RS filters execute before CDI interceptors in the request
 *       lifecycle).
 * </ul>
 *
 * @see TenantScopeInterceptor
 * @see com.koostack.operio.rest.context.OperioRequestHolder
 * @see TenantContext
 * @see RequestContext
 */
@InterceptorBinding
@Inherited
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantScoped {}
