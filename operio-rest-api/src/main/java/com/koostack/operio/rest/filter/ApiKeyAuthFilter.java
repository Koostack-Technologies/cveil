package com.koostack.operio.rest.filter;

import com.koostack.operio.core.models.IdentityContext;
import com.koostack.operio.core.Operio;
import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.platform.iam.TokenValidationService;
import com.koostack.operio.rest.config.OperioConfig;
import com.koostack.operio.rest.context.OperioRequestHolder;
import com.koostack.operio.rest.lifecycle.BootstrapKeyLifecycleService;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.security.Principal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * JAX-RS request filter that authenticates every incoming request via API key
 * (Bearer token) or the one-time bootstrap key.
 *
 * <p>This is the first filter in the authentication chain. It runs at
 * {@link Priorities#AUTHENTICATION} priority, before authorization filters,
 * rate limiting, and resource method invocation.
 *
 * <h2>Authentication Flow</h2>
 * <ol>
 *   <li><strong>Public paths</strong> — health check endpoints are skipped
 *       (unauthenticated access allowed for Kubernetes probes).</li>
 *   <li><strong>CORS preflight</strong> — {@code OPTIONS} requests are
 *       skipped (handled by Quarkus CORS filter).</li>
 *   <li><strong>Extract Bearer token</strong> — reads the
 *       {@code Authorization: Bearer <token>} header.</li>
 *   <li><strong>Bootstrap key check</strong> — if the token matches the
 *       configured {@code operio.super-admin.bootstrap-key}, the request
 *       is granted {@code super-admin} role with no tenant scope. This
 *       allows initial platform setup before any real API keys exist.</li>
 *   <li><strong>Token verification</strong> — delegates to
 *       {@link TokenValidationService#verifyToken(String)} (the
 *       {@code ApiKeyTokenVerifier} implementation SHA-256 hashes the token
 *       and looks it up in the platform {@code api_key} table).</li>
 *   <li><strong>Security context</strong> — on success, sets a JAX-RS
 *       {@link SecurityContext} with the authenticated principal and roles,
 *       and stores the full {@link IdentityContext} as a request property
 *       for downstream consumption (e.g. {@code TenantScopeInterceptor}).</li>
 *   <li><strong>401 on failure</strong> — aborts with HTTP 401 and a JSON
 *       error body if authentication fails.</li>
 * </ol>
 *
 * <h2>Important</h2>
 * <p>This filter does <strong>not</strong> call
 * {@code TenantContext.runWhere()} because JAX-RS filters cannot wrap the
 * resource method invocation. {@code ScopedValue} binding is handled by a
 * separate CDI interceptor ({@code TenantScopeInterceptor}) that reads the
 * {@link IdentityContext} from the request property set here.
 *
 * @see com.koostack.operio.core.platform.iam.TokenValidationService
 * @see OperioConfig.SuperAdminConfig
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiKeyAuthFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(
        ApiKeyAuthFilter.class
    );

    /**
     * Request property key under which the authenticated
     * {@link IdentityContext} is stored. Downstream filters and interceptors
     * (e.g. {@code TenantScopeInterceptor}) read this property to bind
     * {@code TenantContext} and {@code RequestContext}.
     */
    public static final String IDENTITY_CONTEXT_PROPERTY =
        "operio.identity-context";

    /** Standard HTTP Bearer authentication scheme prefix. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Authentication scheme name set on the JAX-RS {@link SecurityContext}. */
    private static final String AUTH_SCHEME_BEARER = "BEARER";

    /** Subject name for bootstrap-key authenticated requests. */
    private static final String BOOTSTRAP_SUBJECT = "bootstrap-admin";

    /** Provider name for bootstrap-key authenticated requests. */
    private static final String BOOTSTRAP_PROVIDER = "bootstrap-key";

    // -----------------------------------------------------------------------
    // Injected dependencies
    // -----------------------------------------------------------------------

    /**
     * The primary Operio instance — used to obtain the {@link TokenValidationService}
     * at request time. We inject {@code Operio} rather than
     * {@code TokenValidationService} directly because the token validation service producer can
     * return {@code null} in embedded mode, and CDI client proxies for
     * {@code @ApplicationScoped} null beans cause NPE on method invocation.
     */
    @Inject
    Operio operio;

    /**
     * Operio configuration — used to read the bootstrap key from
     * {@code operio.super-admin.bootstrap-key}.
     *
     * <p>Injected as {@code Instance<OperioConfig>} (lazy) rather than
     * directly because this filter is eagerly instantiated by RESTEasy
     * Reactive during the Quarkus static-init deployment phase, before
     * the runtime {@link io.smallrye.config.SmallRyeConfig} (which
     * holds the {@code @ConfigMapping} registrations) has been built.
     * Using {@code Instance} defers the actual config-mapping resolution
     * to first access at request time, when the runtime config is
     * available.
     */
    @Inject
    Instance<OperioConfig> configInstance;

    /**
     * Bootstrap key lifecycle service — manages the bootstrap key's
     * active/consumed state, including auto-disable after the first
     * API key is created and the force-override escape hatch.
     *
     * <p>Replaces the previous inline {@code isBootstrapKey()} check
     * with lifecycle-aware validation that rejects consumed bootstrap
     * keys with a descriptive 403 instead of silently falling through
     * to the token verifier (which would return 401 "invalid token").
     *
     * @see BootstrapKeyLifecycleService
     */
    @Inject
    BootstrapKeyLifecycleService bootstrapLifecycle;

    /**
     * Request-scoped holder that carries the authenticated
     * {@link IdentityContext} and HTTP metadata to the
     * {@code TenantScopeInterceptor}. Populated on successful
     * authentication so the interceptor can bind {@code TenantContext}
     * and {@code RequestContext} scoped values around the resource
     * method invocation.
     *
     * @see com.koostack.operio.rest.interceptor.TenantScopeInterceptor
     */
    @Inject
    OperioRequestHolder requestHolder;

    // -----------------------------------------------------------------------
    // Filter implementation
    // -----------------------------------------------------------------------

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        // 1. Skip public paths (health checks, Quarkus built-in endpoints)
        String path = ctx.getUriInfo().getPath();
        if (isPublicPath(path)) {
            LOG.trace("Skipping authentication for public path: {}", path);
            return;
        }

        // 2. Skip CORS preflight requests (handled by Quarkus CORS filter)
        if ("OPTIONS".equalsIgnoreCase(ctx.getMethod())) {
            LOG.trace("Skipping authentication for OPTIONS preflight");
            return;
        }

        // 3. Extract Bearer token from Authorization header
        String authHeader = ctx.getHeaderString("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            abortUnauthorized(
                ctx,
                "Missing or invalid Authorization header — " +
                    "expected 'Bearer <token>'"
            );
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            abortUnauthorized(ctx, "Bearer token must not be empty");
            return;
        }

        // 4. Check bootstrap key (special case — super-admin, no tenant)
        //    Delegates to BootstrapKeyLifecycleService which manages the
        //    active/consumed state of the bootstrap key.
        if (bootstrapLifecycle.isActiveBootstrapKey(token)) {
            LOG.info(
                "Bootstrap key authentication — granting super-admin access"
            );
            IdentityContext identity = new IdentityContext(
                BOOTSTRAP_SUBJECT,
                token,
                null,
                Set.of("super-admin"),
                Map.of(),
                null, // no tenant scope for bootstrap
                BOOTSTRAP_PROVIDER
            );
            applyAuthentication(
                ctx,
                identity,
                resolveRemoteAddress(ctx),
                ctx.getHeaderString("User-Agent")
            );
            return;
        }

        // 4b. If the token matches the bootstrap key value but the key
        //     has been auto-disabled, return 403 Forbidden with a clear
        //     message instead of falling through to token verification
        //     (which would confusingly return 401 "invalid token").
        if (bootstrapLifecycle.isBootstrapKeyValue(token)) {
            LOG.warn(
                "Bootstrap key authentication rejected — key has been " +
                    "auto-disabled after API key creation. Use a real " +
                    "API key or set OPERIO_FORCE_BOOTSTRAP=true for " +
                    "disaster recovery."
            );
            ctx.abortWith(
                Response.status(Response.Status.FORBIDDEN)
                    .entity(
                        Map.of(
                            "error",
                            "Forbidden",
                            "message",
                            "Bootstrap key has been disabled — real API keys " +
                                "exist on the platform. Use a regular API key " +
                                "for authentication, or set " +
                                "OPERIO_FORCE_BOOTSTRAP=true if disaster " +
                                "recovery is needed."
                        )
                    )
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .build()
            );
            return;
        }

        // 4c. Check config-based API keys (dev/test convenience)
        IdentityContext configIdentity = resolveConfigApiKey(token);
        if (configIdentity != null) {
            LOG.debug(
                "Config-based API key authentication — subject={}, tenant={}, roles={}",
                configIdentity.subject(),
                configIdentity.tenantId(),
                configIdentity.roles()
            );
            applyAuthentication(
                ctx,
                configIdentity,
                resolveRemoteAddress(ctx),
                ctx.getHeaderString("User-Agent")
            );
            return;
        }

        // 5. Verify via TokenValidationService (API key hash lookup)
        TokenValidationService<?> tokenValidationService = operio.tokenValidationService();
        if (tokenValidationService == null) {
            LOG.error(
                "No TokenValidationService configured — cannot authenticate requests. " +
                    "Is operio.mode=production with platform-db configured?"
            );
            abortUnauthorized(
                ctx,
                "Authentication service not available — " +
                    "server configuration error"
            );
            return;
        }

        try {
            IdentityContext identity = tokenValidationService.verifyToken(token);
            LOG.debug(
                "Authenticated: subject={}, tenantId={}, provider={}, roles={}",
                identity.subject(),
                identity.tenantId(),
                identity.provider(),
                identity.roles()
            );
            applyAuthentication(
                ctx,
                identity,
                resolveRemoteAddress(ctx),
                ctx.getHeaderString("User-Agent")
            );
        } catch (OperioAuthException e) {
            LOG.debug("Authentication failed: {}", e.getMessage());
            abortUnauthorized(ctx, e.getMessage());
        } catch (Exception e) {
            // Unexpected error during verification — log at error level but
            // still return 401 to the caller (don't leak internal details).
            LOG.error("Unexpected error during token verification", e);
            abortUnauthorized(ctx, "Authentication failed — internal error");
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves a config-based API key from {@code operio.api-keys.*}.
     *
     * <p>Parses the comma-separated value string to extract roles
     * ({@code role:X}) and tenant binding ({@code tenant:X}).
     *
     * @param token the bearer token to look up
     * @return the resolved identity, or {@code null} if not a config key
     */
    private IdentityContext resolveConfigApiKey(String token) {
        OperioConfig config = configInstance.get();
        Map<String, String> apiKeys = config.apiKeys();
        if (apiKeys == null || apiKeys.isEmpty()) {
            return null;
        }

        String spec = apiKeys.get(token);
        if (spec == null) {
            return null;
        }

        Set<String> roles = new HashSet<>();
        String tenantId = null;

        for (String directive : spec.split(",")) {
            String trimmed = directive.trim();
            if (trimmed.startsWith("role:")) {
                roles.add(trimmed.substring("role:".length()));
            } else if (trimmed.startsWith("tenant:")) {
                tenantId = trimmed.substring("tenant:".length());
            }
        }

        return new IdentityContext(
            "config-api-key:" + token,
            token,
            null,
            roles,
            Map.of(),
            tenantId,
            "config-api-key"
        );
    }

    /**
     * Applies the authenticated identity to the request context:
     * <ol>
     *   <li>Populates the {@link OperioRequestHolder} with the identity and
     *       HTTP metadata so the {@code TenantScopeInterceptor} can bind
     *       {@code TenantContext} and {@code RequestContext} scoped values.</li>
     *   <li>Sets the JAX-RS {@link SecurityContext} with the principal and
     *       role information (enables {@code @RolesAllowed} on resources).</li>
     *   <li>Stores the full {@link IdentityContext} as a request property
     *       for backward-compatible downstream consumption.</li>
     * </ol>
     *
     * @param ctx           the request context
     * @param identity      the authenticated identity
     * @param remoteAddress the resolved remote IP address (may be {@code null})
     * @param userAgent     the User-Agent header value (may be {@code null})
     */
    private void applyAuthentication(
        ContainerRequestContext ctx,
        IdentityContext identity,
        String remoteAddress,
        String userAgent
    ) {
        // Populate the request-scoped holder for TenantScopeInterceptor
        requestHolder.set(identity, remoteAddress, userAgent);

        // Store IdentityContext as request property for downstream use
        ctx.setProperty(IDENTITY_CONTEXT_PROPERTY, identity);

        // Enrich SLF4J MDC with identity context so log lines emitted
        // during resource method execution include tenantId and subject.
        // The RequestTracingFilter already set placeholder values ("-")
        // for these keys; we upgrade them to real values now that auth
        // has succeeded.
        if (identity.tenantId() != null && !identity.tenantId().isBlank()) {
            MDC.put(RequestTracingFilter.MDC_TENANT_ID, identity.tenantId());
        }
        if (identity.subject() != null && !identity.subject().isBlank()) {
            MDC.put(RequestTracingFilter.MDC_SUBJECT, identity.subject());
        }

        // Set JAX-RS SecurityContext for @RolesAllowed and
        // SecurityContext injection in resources
        boolean isSecure = ctx.getSecurityContext().isSecure();
        ctx.setSecurityContext(new OperioSecurityContext(identity, isSecure));
    }

    /**
     * Checks whether the given token matches the configured bootstrap key.
     *
     * <p>Returns {@code false} if no bootstrap key is configured or if
     * the key is blank (i.e. the env var is not set).
     *
     * @param token the bearer token to check
     * @return {@code true} if the token is the bootstrap key
     * @deprecated Replaced by {@link BootstrapKeyLifecycleService#isActiveBootstrapKey(String)}
     *             which adds auto-disable and force-override semantics.
     *             Retained as a private helper for any future fallback needs.
     */
    @Deprecated
    @SuppressWarnings("unused")
    private boolean isBootstrapKey(String token) {
        return configInstance
            .get()
            .superAdmin()
            .flatMap(OperioConfig.SuperAdminConfig::bootstrapKey)
            .filter(key -> !key.isBlank())
            .map(key -> key.equals(token))
            .orElse(false);
    }

    /**
     * Resolves the remote (client) IP address from the request headers,
     * falling back through standard proxy headers.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code X-Forwarded-For} — the de-facto standard header set by
     *       reverse proxies and load balancers. If the header contains
     *       multiple comma-separated addresses, the <strong>first</strong>
     *       entry (closest to the client) is used.</li>
     *   <li>{@code X-Real-IP} — alternative header used by some proxies
     *       (e.g. nginx).</li>
     *   <li>{@code null} — if neither header is present. In production
     *       deployments behind a load balancer at least one of these
     *       headers should always be set.</li>
     * </ol>
     *
     * @param ctx the request context
     * @return the resolved remote address, or {@code null}
     */
    private static String resolveRemoteAddress(ContainerRequestContext ctx) {
        // X-Forwarded-For may contain a chain: "client, proxy1, proxy2"
        String xff = ctx.getHeaderString("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // Take the first (leftmost) entry — the original client IP
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }

        String realIp = ctx.getHeaderString("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return null;
    }

    /**
     * Determines whether the given request path is a public endpoint that
     * does not require authentication.
     *
     * <p>Public paths include:
     * <ul>
     *   <li>{@code q/health}, {@code q/health/*} — Quarkus SmallRye Health
     *       built-in endpoints (used by Kubernetes liveness/readiness
     *       probes)</li>
     *   <li>{@code api/v1/health} — application-level health endpoint</li>
     * </ul>
     *
     * @param path the request URI path (without leading slash)
     * @return {@code true} if the path is public
     */
    private static boolean isPublicPath(String path) {
        // Normalize: strip leading slash if present
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return (
            normalized.startsWith("q/health") ||
            normalized.startsWith("api/v1/health")
        );
    }

    /**
     * Aborts the request with HTTP 401 Unauthorized and a JSON error body.
     *
     * @param ctx     the request context
     * @param message the error message to include in the response body
     */
    private static void abortUnauthorized(
        ContainerRequestContext ctx,
        String message
    ) {
        ctx.abortWith(
            Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Unauthorized", "message", message))
                .type(MediaType.APPLICATION_JSON_TYPE)
                .build()
        );
    }

    // -----------------------------------------------------------------------
    // SecurityContext implementation
    // -----------------------------------------------------------------------

    /**
     * Simple {@link SecurityContext} implementation backed by an
     * {@link IdentityContext}.
     *
     * <p>Enables standard Jakarta Security annotations
     * ({@code @RolesAllowed}, {@code @PermitAll}) and programmatic
     * security context access in JAX-RS resources.
     */
    private record OperioSecurityContext(
        IdentityContext identity,
        boolean secure
    ) implements SecurityContext {
        @Override
        public Principal getUserPrincipal() {
            return identity::subject;
        }

        @Override
        public boolean isUserInRole(String role) {
            return (
                role != null &&
                identity.roles() != null &&
                identity.roles().contains(role)
            );
        }

        @Override
        public boolean isSecure() {
            return secure;
        }

        @Override
        public String getAuthenticationScheme() {
            return AUTH_SCHEME_BEARER;
        }
    }
}
