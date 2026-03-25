package com.koostack.cveil.site.filter;

import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.AuthorizationContext;
import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.core.platform.iam.IdpProvider;
import com.koostack.cveil.core.platform.iam.IdpTenantResolver;
import com.koostack.cveil.core.platform.iam.PermissionService;
import com.koostack.cveil.site.config.CveilConfig;
import com.koostack.cveil.site.config.PlatformCqrsHolder;
import com.koostack.cveil.site.context.CveilRequestHolder;
import com.koostack.cveil.site.lifecycle.BootstrapKeyLifecycleService;
import com.koostack.cveil.site.util.AuthCookieHelper;
import com.koostack.cveil.site.util.TenantIdDeriver;
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
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * JAX-RS request filter that authenticates every incoming request to the cveil-site module via
 * cookie-based JWT, Bearer JWT, or the one-time bootstrap key.
 *
 * <p>This is the site equivalent of the REST API's {@code ApiKeyAuthFilter}, simplified for
 * browser-based authentication. It does <strong>not</strong> handle API keys or personal access
 * tokens (PATs) — those are REST API concerns only.
 *
 * <h2>Authentication Flow</h2>
 *
 * <ol>
 *   <li><strong>Public paths</strong> — landing pages, auth pages, static resources, and health
 *       endpoints are skipped (unauthenticated access allowed).
 *   <li><strong>CORS preflight</strong> — {@code OPTIONS} requests are skipped (handled by Quarkus
 *       CORS filter).
 *   <li><strong>Cookie auth</strong> — checks for a JWT in the {@code cveil_access} HttpOnly cookie
 *       (set during browser login). If present and valid, authenticates the request.
 *   <li><strong>Bearer/DPoP header</strong> — falls back to the {@code Authorization} header for
 *       programmatic access or DPoP-bound tokens.
 *   <li><strong>Bootstrap key</strong> — if the token matches the configured bootstrap key, grants
 *       super-admin access for initial platform setup.
 *   <li><strong>JWT verification</strong> — verifies the token via the tenant's {@link IdpProvider}
 *       (Keycloak JWKS-based signature verification).
 *   <li><strong>401 on failure</strong> — aborts with HTTP 401 and a JSON error body if
 *       authentication fails.
 * </ol>
 *
 * @see com.koostack.cveil.site.util.AuthCookieHelper
 * @see BootstrapKeyLifecycleService
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class SiteAuthFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(SiteAuthFilter.class);

    /**
     * Request property key under which the authenticated {@link IdentityContext} is stored.
     * Downstream filters and interceptors (e.g. {@code TenantScopeInterceptor}) read this property
     * to bind {@code TenantContext} and {@code RequestContext}.
     */
    public static final String IDENTITY_CONTEXT_PROPERTY = "cveil.identity-context";

    /** Standard HTTP Bearer authentication scheme prefix. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** DPoP authentication scheme prefix (RFC 9449). */
    private static final String DPOP_PREFIX = "DPoP ";

    /** Authentication scheme name set on the JAX-RS {@link SecurityContext}. */
    private static final String AUTH_SCHEME_BEARER = "BEARER";

    /** Subject name for bootstrap-key authenticated requests. */
    private static final String BOOTSTRAP_SUBJECT = "bootstrap-admin";

    /** Provider name for bootstrap-key authenticated requests. */
    private static final String BOOTSTRAP_PROVIDER = "bootstrap-key";

    /**
     * HTTP header for selecting the active organization for JWT-authenticated requests. When
     * present, the filter validates the user's org membership and uses the org as the active tenant
     * instead of the user's personal tenant.
     */
    public static final String ORG_HEADER = "X-Org-Id";

    // -----------------------------------------------------------------------
    // Injected dependencies
    // -----------------------------------------------------------------------

    @Inject PlatformCqrsHolder cqrsHolder;

    /**
     * Cveil configuration — injected lazily because this filter is eagerly instantiated during
     * Quarkus static-init, before runtime config is available.
     */
    @Inject Instance<CveilConfig> configInstance;

    @Inject BootstrapKeyLifecycleService bootstrapLifecycle;

    @Inject CveilRequestHolder requestHolder;

    @Inject Instance<IdpTenantResolver> idpTenantResolverInstance;

    // -----------------------------------------------------------------------
    // Filter implementation
    // -----------------------------------------------------------------------

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        // 1. Skip public paths (landing pages, auth pages, static resources, health)
        String path = ctx.getUriInfo().getPath();
        if (isPublicPath(path, ctx.getMethod())) {
            LOG.trace("Skipping authentication for public path: {}", path);
            return;
        }

        // 2. Skip CORS preflight requests (handled by Quarkus CORS filter)
        if ("OPTIONS".equalsIgnoreCase(ctx.getMethod())) {
            LOG.trace("Skipping authentication for OPTIONS preflight");
            return;
        }

        // 3. Try cookie-based auth first (browser sessions)
        String cookieHeader = ctx.getHeaderString("Cookie");
        String cookieToken =
                AuthCookieHelper.readCookie(cookieHeader, AuthCookieHelper.ACCESS_COOKIE);
        if (cookieToken != null && !cookieToken.isEmpty()) {
            if (isJwt(cookieToken)) {
                LOG.debug(
                        "Cookie auth: found JWT in {} cookie, len={}",
                        AuthCookieHelper.ACCESS_COOKIE,
                        cookieToken.length());
                verifyJwt(ctx, cookieToken, false);
                return;
            }
        }

        // 4. Fall back to Authorization header (Bearer or DPoP)
        String authHeader = ctx.getHeaderString("Authorization");
        boolean isDpopScheme = false;
        if (authHeader != null && authHeader.startsWith(DPOP_PREFIX)) {
            isDpopScheme = true;
        } else if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            abortUnauthorized(
                    ctx,
                    "Missing or invalid Authorization header —"
                            + " expected 'Bearer <token>' or a valid session cookie");
            return;
        }

        String token =
                authHeader
                        .substring(isDpopScheme ? DPOP_PREFIX.length() : BEARER_PREFIX.length())
                        .trim();
        if (token.isEmpty()) {
            abortUnauthorized(ctx, "Token must not be empty");
            return;
        }

        // 5. Check bootstrap key (special case — super-admin, no tenant)
        if (bootstrapLifecycle.isActiveBootstrapKey(token)) {
            LOG.info("Bootstrap key authentication — granting super-admin access");
            IdentityContext identity =
                    new IdentityContext(
                            BOOTSTRAP_SUBJECT,
                            token,
                            null,
                            Set.of("super-admin"),
                            Map.of(),
                            null, // no tenant scope for bootstrap
                            BOOTSTRAP_PROVIDER);
            applyAuthentication(
                    ctx, identity, resolveRemoteAddress(ctx), ctx.getHeaderString("User-Agent"));
            return;
        }

        // 5b. Bootstrap key value present but disabled — return 403 with clear message
        if (bootstrapLifecycle.isBootstrapKeyValue(token)) {
            LOG.warn(
                    "Bootstrap key authentication rejected — key has been "
                            + "disabled. Use a real login or set "
                            + "CVEIL_FORCE_BOOTSTRAP=true for disaster recovery.");
            ctx.abortWith(
                    Response.status(Response.Status.FORBIDDEN)
                            .entity(
                                    Map.of(
                                            "error",
                                            "Forbidden",
                                            "message",
                                            "Bootstrap key has been disabled. Use a regular"
                                                    + " login for authentication, or set"
                                                    + " CVEIL_FORCE_BOOTSTRAP=true if disaster"
                                                    + " recovery is needed."))
                            .type(MediaType.APPLICATION_JSON_TYPE)
                            .build());
            return;
        }

        // 6. JWT detection — verify via the tenant's IdpProvider
        if (isJwt(token)) {
            String tokenSource = isDpopScheme ? "DPoP-header" : "Bearer-header";
            LOG.info(
                    "JWT auth: source={}, len={}, prefix={}…",
                    tokenSource,
                    token.length(),
                    token.substring(0, Math.min(20, token.length())));
            verifyJwt(ctx, token, isDpopScheme);
            return;
        }

        // 7. Unrecognized token format — not bootstrap, not JWT
        abortUnauthorized(ctx, "Invalid credentials");
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Checks whether the bearer token looks like a JWT (three dot-separated base64url segments).
     */
    private static boolean isJwt(String token) {
        int firstDot = token.indexOf('.');
        if (firstDot <= 0) return false;
        int secondDot = token.indexOf('.', firstDot + 1);
        if (secondDot <= firstDot + 1) return false;
        return token.indexOf('.', secondDot + 1) < 0;
    }

    /**
     * Extracts the tenant ID from a JWT's payload without verifying the signature. Used to route
     * the token to the correct IdP for full verification.
     *
     * <p>Looks for {@code tenant_id}, {@code tenantId}, or extracts from the {@code iss} claim
     * (Keycloak issuers end with {@code /realms/{tenantId}}).
     */
    private static String extractTenantFromJwt(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return null;

            String payload =
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

            // Lightweight JSON extraction without a full parser
            String tenantId = extractJsonStringValue(payload, "tenant_id");
            if (tenantId == null) {
                tenantId = extractJsonStringValue(payload, "tenantId");
            }

            // Keycloak 26+ Organizations: the "organization" claim
            if (tenantId == null) {
                tenantId = extractFirstOrganizationAlias(payload);
            }

            if (tenantId == null) {
                // For Keycloak JWTs: iss = http://host/realms/{realmName}
                String issuer = extractJsonStringValue(payload, "iss");
                if (issuer != null && issuer.contains("/realms/")) {
                    String realmName =
                            issuer.substring(issuer.lastIndexOf("/realms/") + "/realms/".length());
                    if ("cveil".equals(realmName)) {
                        String username = extractJsonStringValue(payload, "preferred_username");
                        if (username != null && !username.isBlank()) {
                            tenantId = TenantIdDeriver.derive(username);
                        }
                    } else {
                        tenantId = realmName;
                    }
                }
            }

            return tenantId;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the first organization alias from the Keycloak {@code organization} claim.
     *
     * <p>Keycloak 26+ with Organizations enabled adds: {@code "organization":["org-alias"]}
     */
    private static String extractFirstOrganizationAlias(String json) {
        String marker = "\"organization\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            marker = "\"organizations\"";
            idx = json.indexOf(marker);
        }
        if (idx < 0) return null;

        int afterColon = json.indexOf(':', idx + marker.length());
        if (afterColon < 0) return null;

        int pos = afterColon + 1;
        while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++;
        if (pos >= json.length()) return null;

        char ch = json.charAt(pos);
        if (ch == '[') {
            int quoteStart = json.indexOf('"', pos + 1);
            if (quoteStart < 0) return null;
            int quoteEnd = json.indexOf('"', quoteStart + 1);
            if (quoteEnd <= quoteStart) return null;
            return json.substring(quoteStart + 1, quoteEnd);
        } else if (ch == '{') {
            int quoteStart = json.indexOf('"', pos + 1);
            if (quoteStart < 0) return null;
            int quoteEnd = json.indexOf('"', quoteStart + 1);
            if (quoteEnd <= quoteStart) return null;
            return json.substring(quoteStart + 1, quoteEnd);
        } else if (ch == '"') {
            int quoteEnd = json.indexOf('"', pos + 1);
            if (quoteEnd <= pos + 1) return null;
            return json.substring(pos + 1, quoteEnd);
        }

        return null;
    }

    /**
     * Minimal JSON string value extraction — avoids a full JSON parser dependency in the auth
     * filter hot path.
     */
    private static String extractJsonStringValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex < 0) return null;

        int quoteStart = json.indexOf('"', colonIndex + 1);
        if (quoteStart < 0) return null;

        int pos = quoteStart + 1;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '"' && json.charAt(pos - 1) != '\\') {
                return json.substring(quoteStart + 1, pos);
            }
            pos++;
        }
        return null;
    }

    /**
     * Verifies a JWT token via the tenant's IdpProvider.
     *
     * <p>Extracts the tenant ID from the JWT payload (without verification), resolves the
     * IdpProvider for that tenant, and delegates to its {@code verifyToken()} method for full
     * JWKS-based signature verification.
     *
     * @param ctx the request context
     * @param token the JWT access token
     * @param isDpopScheme true if the Authorization header used the DPoP scheme
     */
    private void verifyJwt(ContainerRequestContext ctx, String token, boolean isDpopScheme) {
        IdpTenantResolver idpResolver =
                idpTenantResolverInstance.isResolvable() ? idpTenantResolverInstance.get() : null;

        if (idpResolver == null) {
            LOG.error("JWT received but no IdpTenantResolver configured");
            abortUnauthorized(ctx, "JWT authentication not available — server configuration error");
            return;
        }

        IdpProvider<?> defaultProvider = idpResolver.defaultProvider();
        if (defaultProvider == null || !defaultProvider.isAvailable()) {
            LOG.error("JWT received but no IdpProvider available");
            abortUnauthorized(ctx, "JWT authentication not available — no identity provider");
            return;
        }

        String tenantId = extractTenantFromJwt(token);
        if (tenantId == null || tenantId.isBlank()) {
            abortUnauthorized(ctx, "JWT does not contain a tenant identifier");
            return;
        }

        try {
            IdpProvider<?> idpProvider = idpResolver.resolve(tenantId);

            // Diagnostic: log JWT header for troubleshooting key-mismatch errors
            try {
                String[] jwtParts = token.split("\\.");
                if (jwtParts.length >= 2) {
                    String headerJson =
                            new String(
                                    Base64.getUrlDecoder().decode(jwtParts[0]),
                                    StandardCharsets.UTF_8);
                    LOG.debug(
                            "JWT verification attempt: tenantId={}, isDpop={}, header={}",
                            tenantId,
                            isDpopScheme,
                            headerJson);
                }
            } catch (Exception diagEx) {
                LOG.debug("Could not decode JWT header for diagnostics: {}", diagEx.getMessage());
            }

            IdentityContext identity = idpProvider.verifyToken(tenantId, token);

            // -- DPoP cnf.jkt check --
            // If the JWT contains a cnf.jkt claim (DPoP-bound token from Keycloak),
            // log it for debugging but skip full DPoP proof verification — the site
            // module does not carry a DpopVerifier. Browser-based flows typically do
            // not use DPoP; if they do, the token is still signature-verified above.
            String expectedJkt = extractCnfJkt(identity);
            if (isDpopScheme || expectedJkt != null) {
                LOG.debug(
                        "DPoP-bound token detected (cnf.jkt={}), isDpopScheme={} — "
                                + "site module does not enforce DPoP proof verification",
                        expectedJkt,
                        isDpopScheme);
            }

            LOG.debug(
                    "JWT authenticated: subject={}, tenantId={}, provider={}, roles={}",
                    identity.subject(),
                    identity.tenantId(),
                    identity.provider(),
                    identity.roles());
            applyAuthentication(
                    ctx, identity, resolveRemoteAddress(ctx), ctx.getHeaderString("User-Agent"));
        } catch (CveilAuthException e) {
            String jwtDiag = "";
            try {
                String[] parts = token.split("\\.");
                if (parts.length >= 2) {
                    jwtDiag =
                            " | JWT header: "
                                    + new String(
                                            Base64.getUrlDecoder().decode(parts[0]),
                                            StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            }
            LOG.warn(
                    "JWT authentication failed: {} (tenantId={}, isDpop={}{}){}",
                    e.getMessage(),
                    extractTenantFromJwt(token),
                    isDpopScheme,
                    jwtDiag,
                    "");
            abortUnauthorized(ctx, e.getMessage());
        } catch (Exception e) {
            LOG.error("Unexpected error during JWT verification", e);
            abortUnauthorized(ctx, "Authentication failed — internal error");
        }
    }

    /**
     * Extracts the {@code cnf.jkt} (JWK thumbprint confirmation) claim from the identity's claims
     * map.
     */
    @SuppressWarnings("unchecked")
    private static String extractCnfJkt(IdentityContext identity) {
        if (identity.claims() == null) return null;
        Object cnf = identity.claims().get("cnf");
        if (cnf instanceof Map<?, ?> cnfMap) {
            Object jkt = cnfMap.get("jkt");
            return jkt instanceof String s ? s : null;
        }
        return null;
    }

    /**
     * Applies the authenticated identity to the request context:
     *
     * <ol>
     *   <li>Resolves the active tenant: the {@code X-Org-Id} header selects an org tenant
     *       (validated against org membership), or the personal tenant is used as fallback.
     *   <li>Resolves permissions via the {@link PermissionService}, building an {@link
     *       AuthorizationContext}.
     *   <li>Populates the {@link CveilRequestHolder} with the identity, authorization, and HTTP
     *       metadata for the {@code TenantScopeInterceptor}.
     *   <li>Sets the JAX-RS {@link SecurityContext} with the principal and role/permission info.
     *   <li>Stores the {@link IdentityContext} as a request property for downstream consumption.
     * </ol>
     */
    private void applyAuthentication(
            ContainerRequestContext ctx,
            IdentityContext identity,
            String remoteAddress,
            String userAgent) {

        // -- Resolve active tenant --
        String activeTenant = identity.tenantId();

        // Check X-Org-Id header for org context switching
        String orgHeader = ctx.getHeaderString(ORG_HEADER);
        if (orgHeader != null && !orgHeader.isBlank()) {
            activeTenant = orgHeader;
            LOG.debug("JWT user selecting org context via X-Org-Id: {}", orgHeader);
        }

        // Also check the cveil_active_org cookie (browser-based org selection)
        if ((orgHeader == null || orgHeader.isBlank())) {
            String cookieHeader = ctx.getHeaderString("Cookie");
            String activeOrg = AuthCookieHelper.readActiveOrg(cookieHeader);
            if (activeOrg != null && !activeOrg.isBlank()) {
                activeTenant = activeOrg;
                LOG.debug("Using active org from cookie: {}", activeOrg);
            }
        }

        // If the active tenant changed, create an updated IdentityContext
        IdentityContext effectiveIdentity = identity;
        if (activeTenant != null && !activeTenant.equals(identity.tenantId())) {
            effectiveIdentity =
                    new IdentityContext(
                            identity.subject(),
                            identity.token(),
                            identity.email(),
                            identity.roles(),
                            identity.claims(),
                            activeTenant,
                            identity.provider());
        }

        // -- Resolve permissions from DB --
        AuthorizationContext authz = null;
        boolean skipDbResolution = BOOTSTRAP_PROVIDER.equals(effectiveIdentity.provider());
        if (!skipDbResolution
                && cqrsHolder.isPermissionServiceAvailable()
                && effectiveIdentity.subject() != null) {
            try {
                PermissionService permSvc = cqrsHolder.permissionService();
                Set<String> roles = permSvc.resolveRoles(effectiveIdentity.subject(), activeTenant);
                Set<String> permissions =
                        permSvc.resolvePermissions(effectiveIdentity.subject(), activeTenant);
                authz =
                        new AuthorizationContext(
                                effectiveIdentity.subject(), activeTenant, roles, permissions);
            } catch (Exception e) {
                LOG.warn(
                        "Failed to resolve permissions for subject={}, tenant={}: {}",
                        effectiveIdentity.subject(),
                        activeTenant,
                        e.getMessage());
            }
        }

        // Fallback: build AuthorizationContext from identity roles
        if (authz == null) {
            authz =
                    new AuthorizationContext(
                            effectiveIdentity.subject(),
                            activeTenant,
                            effectiveIdentity.roles() != null
                                    ? effectiveIdentity.roles()
                                    : Set.of(),
                            Set.of());
        }

        // Populate request-scoped holder for TenantScopeInterceptor
        requestHolder.set(effectiveIdentity, remoteAddress, userAgent);
        requestHolder.setAuthorization(authz);

        // Store IdentityContext as request property for downstream use
        ctx.setProperty(IDENTITY_CONTEXT_PROPERTY, effectiveIdentity);

        // Enrich SLF4J MDC with identity context
        if (effectiveIdentity.tenantId() != null && !effectiveIdentity.tenantId().isBlank()) {
            MDC.put(RequestTracingFilter.MDC_TENANT_ID, effectiveIdentity.tenantId());
        }
        if (effectiveIdentity.subject() != null && !effectiveIdentity.subject().isBlank()) {
            MDC.put(RequestTracingFilter.MDC_SUBJECT, effectiveIdentity.subject());
        }

        // Set JAX-RS SecurityContext
        boolean isSecure = ctx.getSecurityContext().isSecure();
        ctx.setSecurityContext(new SiteSecurityContext(effectiveIdentity, authz, isSecure));
    }

    /**
     * Resolves the remote (client) IP address from proxy headers.
     *
     * <p>Resolution order: {@code X-Forwarded-For} (first entry) → {@code X-Real-IP} → {@code
     * null}.
     */
    private static String resolveRemoteAddress(ContainerRequestContext ctx) {
        String xff = ctx.getHeaderString("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
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
     * Determines whether the given request path is a public endpoint that does not require
     * authentication.
     *
     * <p>Public paths include landing pages, authentication pages, static resources, Quarkus
     * health/metrics endpoints, and public API endpoints.
     *
     * @param path the request URI path
     * @param method the HTTP method
     * @return {@code true} if the path is public
     */
    private static boolean isPublicPath(String path, String method) {
        // Normalize: strip leading slash if present
        String normalized = path.startsWith("/") ? path.substring(1) : path;

        // Landing and auth pages
        if (normalized.isEmpty()
                || normalized.equals("signup")
                || normalized.equals("login")
                || normalized.equals("forgot-password")
                || normalized.equals("reset-password")
                || normalized.startsWith("verify-email/callback")) {
            return true;
        }

        // Quarkus built-in health/metrics endpoints (Kubernetes probes)
        if (normalized.startsWith("q/")) {
            return true;
        }

        // Public consent/DSAR API endpoints
        if (normalized.startsWith("api/v1/public/")) {
            return true;
        }

        // IAM auth endpoints (login, refresh, logout must be accessible without auth)
        if (normalized.equals("api/v1/platform/iam/login")
                || normalized.equals("api/v1/platform/iam/refresh")
                || normalized.equals("api/v1/platform/iam/logout")) {
            return true;
        }

        // Self-service user registration (POST only) — the resource itself
        // validates the bootstrap key. GET /users requires authentication.
        if (normalized.equals("api/v1/platform/iam/users") && "POST".equalsIgnoreCase(method)) {
            return true;
        }

        // Static resources — bypass auth for browser-loaded assets
        if (isStaticResource(path)) {
            return true;
        }

        return false;
    }

    /** Checks whether the path refers to a static resource that should bypass authentication. */
    private static boolean isStaticResource(String path) {
        return path.endsWith(".js")
                || path.endsWith(".css")
                || path.endsWith(".html")
                || path.endsWith(".png")
                || path.endsWith(".svg")
                || path.endsWith(".ico")
                || path.endsWith(".woff")
                || path.endsWith(".woff2")
                || path.endsWith(".map");
    }

    /** Aborts the request with HTTP 401 Unauthorized and a JSON error body. */
    private static void abortUnauthorized(ContainerRequestContext ctx, String message) {
        String path = ctx.getUriInfo().getPath();
        // For page requests (not API calls), redirect to /login instead of JSON 401
        if (!path.startsWith("/api/") && !path.startsWith("api/")) {
            ctx.abortWith(
                    Response.seeOther(java.net.URI.create("/login"))
                            .build());
            return;
        }
        ctx.abortWith(
                Response.status(Response.Status.UNAUTHORIZED)
                        .entity(Map.of("error", "Unauthorized", "message", message))
                        .type(MediaType.APPLICATION_JSON_TYPE)
                        .build());
    }

    // -----------------------------------------------------------------------
    // SecurityContext implementation
    // -----------------------------------------------------------------------

    /**
     * {@link SecurityContext} implementation backed by an {@link IdentityContext} and {@link
     * AuthorizationContext}. Checks both legacy identity roles and DB-backed permissions.
     */
    private record SiteSecurityContext(
            IdentityContext identity, AuthorizationContext authorization, boolean secure)
            implements SecurityContext {
        @Override
        public Principal getUserPrincipal() {
            return identity::subject;
        }

        @Override
        public boolean isUserInRole(String role) {
            if (role != null && identity.roles() != null && identity.roles().contains(role)) {
                return true;
            }
            if (authorization != null) {
                if (authorization.hasRole(role)) return true;
                if (authorization.hasPermission(role)) return true;
            }
            return false;
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
