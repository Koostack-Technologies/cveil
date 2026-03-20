package com.koostack.cveil.rest.filter;

import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.AuthorizationContext;
import com.koostack.cveil.core.models.IdentityContext;
import com.koostack.cveil.core.platform.iam.AccessTokenGenerator;
import com.koostack.cveil.core.platform.iam.AccessTokenInfo;
import com.koostack.cveil.core.platform.iam.IdpProvider;
import com.koostack.cveil.core.platform.iam.IdpTenantResolver;
import com.koostack.cveil.core.platform.iam.PermissionService;
import com.koostack.cveil.rest.billing.BillableEndpoints;
import com.koostack.cveil.rest.config.CveilConfig;
import com.koostack.cveil.rest.config.PlatformCqrsHolder;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.lifecycle.BootstrapKeyLifecycleService;
import com.koostack.cveil.rest.util.AuthCookieHelper;
import com.koostack.cveil.rest.util.TenantIdDeriver;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * JAX-RS request filter that authenticates every incoming request via API key (Bearer token) or the
 * one-time bootstrap key.
 *
 * <p>This is the first filter in the authentication chain. It runs at {@link
 * Priorities#AUTHENTICATION} priority, before authorization filters, rate limiting, and resource
 * method invocation.
 *
 * <h2>Authentication Flow</h2>
 *
 * <ol>
 *   <li><strong>Public paths</strong> — health check endpoints are skipped (unauthenticated access
 *       allowed for Kubernetes probes).
 *   <li><strong>CORS preflight</strong> — {@code OPTIONS} requests are skipped (handled by Quarkus
 *       CORS filter).
 *   <li><strong>Extract Bearer token</strong> — reads the {@code Authorization: Bearer <token>}
 *       header.
 *   <li><strong>Bootstrap key check</strong> — if the token matches the configured {@code
 *       cveil.super-admin.bootstrap-key}, the request is granted {@code super-admin} role with no
 *       tenant scope. This allows initial platform setup before any real API keys exist.
 *   <li><strong>Token verification</strong> — for {@code cveil_} prefixed tokens, validates the
 *       CRC32 checksum, computes a SHA-256 hash, and looks it up in the {@code
 *       cveiliam.access_token} table. For JWT tokens (three dot-separated segments), delegates to
 *       the tenant's {@link IdpProvider} for JWKS-based verification.
 *   <li><strong>Security context</strong> — on success, sets a JAX-RS {@link SecurityContext} with
 *       the authenticated principal and roles, and stores the full {@link IdentityContext} as a
 *       request property for downstream consumption (e.g. {@code TenantScopeInterceptor}).
 *   <li><strong>401 on failure</strong> — aborts with HTTP 401 and a JSON error body if
 *       authentication fails.
 * </ol>
 *
 * <h2>Important</h2>
 *
 * <p>This filter does <strong>not</strong> call {@code TenantContext.runWhere()} because JAX-RS
 * filters cannot wrap the resource method invocation. {@code ScopedValue} binding is handled by a
 * separate CDI interceptor ({@code TenantScopeInterceptor}) that reads the {@link IdentityContext}
 * from the request property set here.
 *
 * @see com.koostack.cveil.core.platform.iam.TokenValidationService
 * @see CveilConfig.SuperAdminConfig
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiKeyAuthFilter implements ContainerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

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

    /**
     * Holder for platform CQRS services including token validation. Uses a concrete non-generic
     * type to avoid ArC raw-type resolution issues.
     */
    @Inject PlatformCqrsHolder cqrsHolder;

    /**
     * Cveil configuration — used to read the bootstrap key from {@code
     * cveil.super-admin.bootstrap-key}.
     *
     * <p>Injected as {@code Instance<CveilConfig>} (lazy) rather than directly because this filter
     * is eagerly instantiated by RESTEasy Reactive during the Quarkus static-init deployment phase,
     * before the runtime {@link io.smallrye.config.SmallRyeConfig} (which holds the
     * {@code @ConfigMapping} registrations) has been built. Using {@code Instance} defers the
     * actual config-mapping resolution to first access at request time, when the runtime config is
     * available.
     */
    @Inject Instance<CveilConfig> configInstance;

    /**
     * Bootstrap key lifecycle service — manages the bootstrap key's active/consumed state,
     * including auto-disable after the first API key is created and the force-override escape
     * hatch.
     *
     * <p>Replaces the previous inline {@code isBootstrapKey()} check with lifecycle-aware
     * validation that rejects consumed bootstrap keys with a descriptive 403 instead of silently
     * falling through to the token verifier (which would return 401 "invalid token").
     *
     * @see BootstrapKeyLifecycleService
     */
    @Inject BootstrapKeyLifecycleService bootstrapLifecycle;

    /**
     * Request-scoped holder that carries the authenticated {@link IdentityContext} and HTTP
     * metadata to the {@code TenantScopeInterceptor}. Populated on successful authentication so the
     * interceptor can bind {@code TenantContext} and {@code RequestContext} scoped values around
     * the resource method invocation.
     *
     * @see com.koostack.cveil.rest.interceptor.TenantScopeInterceptor
     */
    @Inject CveilRequestHolder requestHolder;

    /**
     * Tenant-aware IdP resolver for JWT verification. Resolves which {@link IdpProvider} to use for
     * a given tenant (defaults to Keycloak, overridable per tenant). May be {@code null} in
     * embedded mode when no IdP is configured.
     */
    @Inject Instance<IdpTenantResolver> idpTenantResolverInstance;

    /** DPoP proof verifier — validates RFC 9449 proof JWTs on incoming requests. */
    private final DpopVerifier dpopVerifier = new DpopVerifier();

    // -----------------------------------------------------------------------
    // Filter implementation
    // -----------------------------------------------------------------------

    @Override
    public void filter(ContainerRequestContext ctx) throws IOException {
        // 1. Skip public paths (health checks, Quarkus built-in endpoints)
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

        // 3. Extract token from Authorization header (DPoP or Bearer)
        String authHeader = ctx.getHeaderString("Authorization");
        boolean isDpopScheme = false;
        if (authHeader != null && authHeader.startsWith(DPOP_PREFIX)) {
            isDpopScheme = true;
        } else if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            abortUnauthorized(
                    ctx,
                    "Missing or invalid Authorization header —"
                            + " expected 'Bearer <token>' or 'DPoP <token>'");
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

        // 4. Check bootstrap key (special case — super-admin, no tenant)
        //    Delegates to BootstrapKeyLifecycleService which manages the
        //    active/consumed state of the bootstrap key.
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

        // 4b. If the token matches the bootstrap key value but the key
        //     has been auto-disabled, return 403 Forbidden with a clear
        //     message instead of falling through to token verification
        //     (which would confusingly return 401 "invalid token").
        if (bootstrapLifecycle.isBootstrapKeyValue(token)) {
            LOG.warn(
                    "Bootstrap key authentication rejected — key has been "
                            + "auto-disabled after API key creation. Use a real "
                            + "API key or set CVEIL_FORCE_BOOTSTRAP=true for "
                            + "disaster recovery.");
            ctx.abortWith(
                    Response.status(Response.Status.FORBIDDEN)
                            .entity(
                                    Map.of(
                                            "error",
                                            "Forbidden",
                                            "message",
                                            "Bootstrap key has been disabled — real API keys exist"
                                                    + " on the platform. Use a regular API key for"
                                                    + " authentication, or set"
                                                    + " CVEIL_FORCE_BOOTSTRAP=true if disaster"
                                                    + " recovery is needed."))
                            .type(MediaType.APPLICATION_JSON_TYPE)
                            .build());
            return;
        }

        // 4c. Check config-based API keys (dev/test convenience)
        IdentityContext configIdentity = resolveConfigApiKey(token);
        if (configIdentity != null) {
            LOG.debug(
                    "Config-based API key authentication — subject={}, tenant={}, roles={}",
                    configIdentity.subject(),
                    configIdentity.tenantId(),
                    configIdentity.roles());
            applyAuthentication(
                    ctx,
                    configIdentity,
                    resolveRemoteAddress(ctx),
                    ctx.getHeaderString("User-Agent"));
            return;
        }

        // 4d. Personal access token (cveil_ prefix) — SHA-256 hash lookup
        //     in the cveiliam.access_token table.
        if (AccessTokenGenerator.isAccessToken(token)) {
            verifyAccessToken(ctx, token);
            return;
        }

        // 4e. JWT detection — if the token looks like a JWT (3 dot-separated
        //     base64url segments), verify via the tenant's IdpProvider.
        if (isJwt(token)) {
            // Log token source for debugging auth issues (WARN for visibility)
            String tokenSource =
                    isDpopScheme
                            ? "DPoP-header"
                            : (ctx.getHeaderString("Authorization") != null
                                    ? "Bearer-header"
                                    : "cookie");
            LOG.info(
                    "JWT auth: source={}, len={}, prefix={}…",
                    tokenSource,
                    token.length(),
                    token.substring(0, Math.min(20, token.length())));
            verifyJwt(ctx, token, isDpopScheme);
            return;
        }

        // 5. Unrecognized token format — not bootstrap, not config, not JWT.
        abortUnauthorized(ctx, "Invalid credentials");
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves a config-based API key from {@code cveil.api-keys.*}.
     *
     * <p>Parses the comma-separated value string to extract roles ({@code role:X}) and tenant
     * binding ({@code tenant:X}).
     *
     * @param token the bearer token to look up
     * @return the resolved identity, or {@code null} if not a config key
     */
    private IdentityContext resolveConfigApiKey(String token) {
        CveilConfig config = configInstance.get();
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
                "config-api-key");
    }

    /**
     * Verifies a {@code cveil_} personal access token by hashing and looking up in the platform
     * database.
     *
     * <p>Flow:
     *
     * <ol>
     *   <li>Fast-reject malformed tokens via CRC32 checksum validation
     *   <li>Compute SHA-256 hash of the raw token
     *   <li>Look up hash in {@code cveiliam.access_token} via CQRS query
     *   <li>Validate status ({@code active}) and expiration
     *   <li>Build {@link IdentityContext} with snapshotted roles
     * </ol>
     */
    private void verifyAccessToken(ContainerRequestContext ctx, String token) {
        // Fast reject: validate CRC32 checksum without touching the DB
        if (!AccessTokenGenerator.validateChecksum(token)) {
            LOG.debug("Access token failed CRC32 checksum validation");
            abortUnauthorized(ctx, "Invalid access token — checksum mismatch");
            return;
        }

        // Ensure CQRS query layer is available
        if (!cqrsHolder.isCqrsAvailable()) {
            LOG.error("Access token received but CQRS query layer not available");
            abortUnauthorized(ctx, "Access token authentication not available — server not ready");
            return;
        }

        String tokenHash = AccessTokenGenerator.sha256Hex(token);
        AccessTokenInfo tokenInfo = cqrsHolder.iamQueryService().findAccessTokenByHash(tokenHash);

        if (tokenInfo == null) {
            LOG.debug("Access token not found (hash prefix: {}…)", tokenHash.substring(0, 8));
            abortUnauthorized(ctx, "Invalid access token");
            return;
        }

        // Validate status
        if (!"active".equalsIgnoreCase(tokenInfo.status())) {
            LOG.debug(
                    "Access token {} is {} — rejecting",
                    tokenInfo.tokenPrefix(),
                    tokenInfo.status());
            abortUnauthorized(ctx, "Invalid access token");
            return;
        }

        // Validate expiration
        if (tokenInfo.expiresAt() == null || tokenInfo.expiresAt().isBefore(Instant.now())) {
            LOG.debug(
                    "Access token {} has expired at {}",
                    tokenInfo.tokenPrefix(),
                    tokenInfo.expiresAt());
            abortUnauthorized(ctx, "Invalid access token");
            return;
        }

        // Parse roles from comma-separated string
        Set<String> roles;
        if (tokenInfo.roles() != null && !tokenInfo.roles().isBlank()) {
            roles =
                    Arrays.stream(tokenInfo.roles().split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toUnmodifiableSet());
        } else {
            roles = Set.of();
        }

        IdentityContext identity =
                new IdentityContext(
                        tokenInfo.createdBy(),
                        token,
                        null,
                        roles,
                        Map.of(
                                "token_prefix", tokenInfo.tokenPrefix(),
                                "token_name", tokenInfo.name()),
                        tokenInfo.tenantId(),
                        "access-token");

        LOG.debug(
                "Access token authenticated: prefix={}, tenant={}, roles={}",
                tokenInfo.tokenPrefix(),
                tokenInfo.tenantId(),
                roles);

        applyAuthentication(
                ctx, identity, resolveRemoteAddress(ctx), ctx.getHeaderString("User-Agent"));
    }

    /**
     * Checks whether the bearer token looks like a JWT (three dot-separated base64url segments).
     */
    private static boolean isJwt(String token) {
        int firstDot = token.indexOf('.');
        if (firstDot <= 0) return false;
        int secondDot = token.indexOf('.', firstDot + 1);
        if (secondDot <= firstDot + 1) return false;
        // Must have exactly two dots (header.payload.signature)
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

            // Keycloak 26+ Organizations: the "organization" claim is an array
            // of org aliases, e.g. ["my-org"]. Use the first entry as tenant ID.
            if (tenantId == null) {
                tenantId = extractFirstOrganizationAlias(payload);
            }

            if (tenantId == null) {
                // For Keycloak JWTs: iss = http://host/realms/{realmName}
                // If the realm is "cveil" (the shared platform realm), derive tenant
                // from the user's preferred_username instead.
                String issuer = extractJsonStringValue(payload, "iss");
                if (issuer != null && issuer.contains("/realms/")) {
                    String realmName =
                            issuer.substring(issuer.lastIndexOf("/realms/") + "/realms/".length());
                    if ("cveil".equals(realmName)) {
                        // Shared platform realm — derive tenant from username
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
     * <p>Keycloak 26+ with Organizations enabled adds: {@code "organization":["org-alias"]} This
     * method finds the first element in that JSON array.
     */
    private static String extractFirstOrganizationAlias(String json) {
        // Try singular "organization" (Keycloak 26 default)
        String marker = "\"organization\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            // Fallback: plural "organizations" (some mappers use this)
            marker = "\"organizations\"";
            idx = json.indexOf(marker);
        }
        if (idx < 0) return null;

        int afterColon = json.indexOf(':', idx + marker.length());
        if (afterColon < 0) return null;

        // Skip whitespace after colon
        int pos = afterColon + 1;
        while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) pos++;
        if (pos >= json.length()) return null;

        char ch = json.charAt(pos);
        if (ch == '[') {
            // Array format: ["org-alias", ...]
            int quoteStart = json.indexOf('"', pos + 1);
            if (quoteStart < 0) return null;
            int quoteEnd = json.indexOf('"', quoteStart + 1);
            if (quoteEnd <= quoteStart) return null;
            return json.substring(quoteStart + 1, quoteEnd);
        } else if (ch == '{') {
            // Object format: {"org-alias": {}, ...}
            int quoteStart = json.indexOf('"', pos + 1);
            if (quoteStart < 0) return null;
            int quoteEnd = json.indexOf('"', quoteStart + 1);
            if (quoteEnd <= quoteStart) return null;
            return json.substring(quoteStart + 1, quoteEnd);
        } else if (ch == '"') {
            // String format: "org-alias"
            int quoteEnd = json.indexOf('"', pos + 1);
            if (quoteEnd <= pos + 1) return null;
            return json.substring(pos + 1, quoteEnd);
        }

        return null;
    }

    /**
     * Minimal JSON string value extraction — avoids a full JSON parser dependency in the auth
     * filter hot path. Handles backslash-escaped quotes.
     */
    private static String extractJsonStringValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) return null;

        int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
        if (colonIndex < 0) return null;

        int quoteStart = json.indexOf('"', colonIndex + 1);
        if (quoteStart < 0) return null;

        // Walk forward handling escaped quotes
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
     * Verifies a JWT token via the tenant's IdpProvider, with optional DPoP proof validation.
     *
     * <p>Extracts the tenant ID from the JWT payload (without verification), resolves the
     * IdpProvider for that tenant, and delegates to its {@code verifyToken()} method for full
     * JWKS-based signature verification. When the request uses the {@code DPoP} authorization
     * scheme, the DPoP proof header is validated and the access token's {@code cnf.jkt} claim is
     * verified against the proof's public key thumbprint.
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

            // -- DPoP proof validation --
            // Extract cnf.jkt from the verified JWT claims (Keycloak adds this
            // when dpop.bound.access.tokens is enabled on the client).
            String expectedJkt = extractCnfJkt(identity);

            if (isDpopScheme || expectedJkt != null) {
                // DPoP proof is required: either the client sent DPoP scheme
                // or the token is DPoP-bound (has cnf.jkt).
                String dpopHeader = ctx.getHeaderString("DPoP");
                if (dpopHeader == null || dpopHeader.isBlank()) {
                    abortUnauthorized(ctx, "DPoP-bound token requires a DPoP proof header");
                    return;
                }

                // Build the htu (scheme + authority + path, no query)
                var requestUri = ctx.getUriInfo().getRequestUri();
                String htu =
                        requestUri.getScheme()
                                + "://"
                                + requestUri.getAuthority()
                                + requestUri.getPath();

                try {
                    dpopVerifier.verify(dpopHeader, ctx.getMethod(), htu, token, expectedJkt);
                } catch (DpopVerifier.DpopVerificationException e) {
                    LOG.debug("DPoP verification failed: {}", e.getMessage());
                    abortUnauthorized(ctx, "DPoP proof invalid: " + e.getMessage());
                    return;
                }
            }

            LOG.debug(
                    "JWT authenticated: subject={}, tenantId={}, provider={}, roles={}, dpop={}",
                    identity.subject(),
                    identity.tenantId(),
                    identity.provider(),
                    identity.roles(),
                    isDpopScheme);
            applyAuthentication(
                    ctx, identity, resolveRemoteAddress(ctx), ctx.getHeaderString("User-Agent"));
        } catch (CveilAuthException e) {
            // Log JWT header for diagnosing key/algorithm mismatch errors
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
     * map. Keycloak adds this claim to DPoP-bound access tokens.
     *
     * @return the JWK thumbprint, or {@code null} if not present
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
     *   <li>Resolves the active tenant: for PATs, the stored tenant_id is authoritative and cannot
     *       be overridden; for JWTs, the {@code X-Org-Id} header selects an org tenant (validated
     *       against org membership), or the personal tenant is used as fallback.
     *   <li>Resolves permissions from the {@code role_assignment} / {@code role_permission} tables
     *       via the {@link PermissionService}, building an {@link AuthorizationContext}.
     *   <li>Populates the {@link CveilRequestHolder} with the identity, authorization, and HTTP
     *       metadata for the {@code TenantScopeInterceptor}.
     *   <li>Sets the JAX-RS {@link SecurityContext} with the principal and role/permission
     *       information.
     *   <li>Stores the full {@link IdentityContext} as a request property for backward-compatible
     *       downstream consumption.
     * </ol>
     *
     * @param ctx the request context
     * @param identity the authenticated identity
     * @param remoteAddress the resolved remote IP address (may be {@code null})
     * @param userAgent the User-Agent header value (may be {@code null})
     */
    private void applyAuthentication(
            ContainerRequestContext ctx,
            IdentityContext identity,
            String remoteAddress,
            String userAgent) {

        // -- Resolve active tenant --
        String activeTenant = identity.tenantId();
        String orgHeader = ctx.getHeaderString(ORG_HEADER);
        boolean isPatAuth = "access-token".equals(identity.provider());

        if (orgHeader != null && !orgHeader.isBlank()) {
            if (isPatAuth) {
                // PATs have a fixed tenant — reject mismatched X-Org-Id
                if (!orgHeader.equals(activeTenant)) {
                    LOG.warn(
                            "PAT tenant mismatch: PAT={}, X-Org-Id={}. PAT tenant is"
                                    + " authoritative.",
                            activeTenant,
                            orgHeader);
                }
                // PAT tenant wins regardless
            } else {
                // JWT user selecting an org context — use the org header as active tenant
                // Org membership validation will happen at the resource level
                // (the permission resolver will return empty permissions if user has no roles in
                // that tenant)
                activeTenant = orgHeader;
                LOG.debug("JWT user selecting org context via X-Org-Id: {}", orgHeader);
            }
        } else if (!isPatAuth) {
            // Fallback: read cveil_active_org cookie for browser-based JWT requests.
            // This ensures dashboard fetch() calls respect the workspace selection
            // without requiring every frontend call to include the X-Org-Id header.
            String cookieHeader = ctx.getHeaderString("Cookie");
            String orgCookie = AuthCookieHelper.readActiveOrg(cookieHeader);
            if (orgCookie != null && !orgCookie.isBlank()) {
                activeTenant = orgCookie;
                LOG.debug("JWT user selecting org context via cookie: {}", orgCookie);
            }
        }

        // If the active tenant changed from what's in the identity, create
        // an updated IdentityContext with the new tenant for downstream consumers
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
        // Skip DB resolution for bootstrap-key auth — its super-admin role is
        // authoritative and there is no corresponding DB user.
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
                // Fall back to identity roles only (no DB-backed permissions)
            }
        }

        // If no DB permissions resolved, build a fallback AuthorizationContext from identity roles
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

        // ---- PAT endpoint restriction ----
        // Personal access tokens may only access the public billable API surface
        // (governance, consent/subjects, catalogue, graph, events). Requests to
        // internal/dashboard endpoints (billing, IAM, metrics, platform, etc.) are
        // rejected with 403 Forbidden to prevent billing bypass.
        // This check runs BEFORE populating request-scoped state so that denied
        // requests don't leak identity into downstream filters or rate limiters.
        if (BillableEndpoints.isPatAuth(effectiveIdentity.provider())) {
            String requestPath = ctx.getUriInfo().getPath();
            if (!requestPath.startsWith("/")) {
                requestPath = "/" + requestPath;
            }
            // Defensive normalization: collapse any "../" or "//" sequences
            // that might bypass prefix matching. Quarkus/Vert.x normalizes
            // before routing, but this guards against edge cases.
            try {
                requestPath = new URI(requestPath).normalize().getPath();
            } catch (URISyntaxException ignored) {
                // Malformed path — treat as non-billable (deny by default)
            }
            if (!BillableEndpoints.isBillable(requestPath)) {
                LOG.warn(
                        "PAT access denied to non-billable endpoint: path={}, subject={},"
                                + " tenant={}",
                        requestPath,
                        effectiveIdentity.subject(),
                        effectiveIdentity.tenantId());
                ctx.abortWith(
                        Response.status(Response.Status.FORBIDDEN)
                                .entity(
                                        Map.of(
                                                "error",
                                                "Forbidden",
                                                "message",
                                                "Personal access tokens can only access the public"
                                                        + " API (consent, governance, catalogue,"
                                                        + " graph, events). Use a session login for"
                                                        + " dashboard endpoints."))
                                .type(MediaType.APPLICATION_JSON_TYPE)
                                .build());
                return;
            }
        }

        // Populate the request-scoped holder for TenantScopeInterceptor
        requestHolder.set(effectiveIdentity, remoteAddress, userAgent);
        requestHolder.setAuthorization(authz);

        // Store IdentityContext as request property for downstream use
        ctx.setProperty(IDENTITY_CONTEXT_PROPERTY, effectiveIdentity);

        // Enrich SLF4J MDC with identity context so log lines emitted
        // during resource method execution include tenantId and subject.
        // The RequestTracingFilter already set placeholder values ("-")
        // for these keys; we upgrade them to real values now that auth
        // has succeeded.
        if (effectiveIdentity.tenantId() != null && !effectiveIdentity.tenantId().isBlank()) {
            MDC.put(RequestTracingFilter.MDC_TENANT_ID, effectiveIdentity.tenantId());
        }
        if (effectiveIdentity.subject() != null && !effectiveIdentity.subject().isBlank()) {
            MDC.put(RequestTracingFilter.MDC_SUBJECT, effectiveIdentity.subject());
        }

        // Set JAX-RS SecurityContext for @RolesAllowed and
        // SecurityContext injection in resources
        boolean isSecure = ctx.getSecurityContext().isSecure();
        ctx.setSecurityContext(new CveilSecurityContext(effectiveIdentity, authz, isSecure));
    }

    /**
     * Resolves the remote (client) IP address from the request headers, falling back through
     * standard proxy headers.
     *
     * <p>Resolution order:
     *
     * <ol>
     *   <li>{@code X-Forwarded-For} — the de-facto standard header set by reverse proxies and load
     *       balancers. If the header contains multiple comma-separated addresses, the
     *       <strong>first</strong> entry (closest to the client) is used.
     *   <li>{@code X-Real-IP} — alternative header used by some proxies (e.g. nginx).
     *   <li>{@code null} — if neither header is present. In production deployments behind a load
     *       balancer at least one of these headers should always be set.
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
     * Determines whether the given request path is a public endpoint that does not require
     * authentication.
     *
     * <p>Public paths include:
     *
     * <ul>
     *   <li>{@code q/health}, {@code q/health/*} — Quarkus SmallRye Health built-in endpoints (used
     *       by Kubernetes liveness/readiness probes)
     *   <li>{@code api/v1/health} — application-level health endpoint
     *   <li>{@code api/v1/docs}, {@code api/v1/docs/openapi.json} — tenant API documentation
     *       (Swagger UI and OpenAPI spec)
     *   <li>{@code api/v1/platform/docs} — platform docs Swagger UI page (the spec endpoint at
     *       {@code api/v1/platform/docs/openapi.json} is <em>not</em> public and requires
     *       super-admin auth)
     * </ul>
     *
     * @param path the request URI path (without leading slash)
     * @param method the HTTP method (GET, POST, etc.)
     * @return {@code true} if the path is public
     */
    private static boolean isPublicPath(String path, String method) {
        // Normalize: strip leading slash if present
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return (normalized.startsWith("q/health")
                || normalized.startsWith("api/v1/health")
                ||
                // Tenant API docs (Swagger UI + OpenAPI spec)
                normalized.startsWith("api/v1/docs/")
                || normalized.equals("api/v1/docs")
                ||
                // Platform docs Swagger UI page only (spec endpoint is NOT public)
                normalized.equals("api/v1/platform/docs")
                || normalized.equals("api/v1/platform/docs/")
                ||
                // IAM auth endpoints (login, refresh, logout) — unauthenticated by design.
                // Self-service user registration (POST only) — GET /users requires super-admin.
                normalized.equals("api/v1/platform/iam/login")
                || normalized.equals("api/v1/platform/iam/refresh")
                || normalized.equals("api/v1/platform/iam/logout")
                || (normalized.equals("api/v1/platform/iam/users")
                        && "POST".equalsIgnoreCase(method))
                ||
                // Site pages (public, unauthenticated)
                normalized.isEmpty()
                || normalized.equals("docs")
                || normalized.equals("docs/")
                || normalized.startsWith("docs/")
                || normalized.equals("plans")
                || normalized.equals("plans/")
                || normalized.equals("signup")
                || normalized.equals("signup/")
                || normalized.equals("signup/success")
                || normalized.equals("signup/success/")
                || normalized.equals("verify-email/callback")
                || normalized.equals("verify-email/callback/")
                || normalized.equals("setup")
                || normalized.equals("setup/")
                || normalized.equals("setup/status")
                || normalized.equals("setup/status/")
                || normalized.equals("onboarding")
                || normalized.equals("onboarding/")
                || normalized.startsWith("onboarding/")
                || normalized.equals("login")
                || normalized.equals("login/")
                || normalized.equals("logout")
                || normalized.equals("logout/")
                // Public consent banner API (unauthenticated — tenant resolved from siteId)
                || normalized.startsWith("api/v1/public/consent/")
                ||
                // Public DSAR self-service submission (unauthenticated)
                normalized.startsWith("api/v1/public/dsar/")
                ||
                // Dashboard uses cookie-based auth (checked in SiteResource)
                normalized.equals("dashboard")
                || normalized.equals("dashboard/")
                || normalized.startsWith("dashboard/billing")
                || normalized.startsWith("dashboard/account")
                || normalized.startsWith("dashboard/governance")
                || normalized.startsWith("dashboard/consent")
                || normalized.startsWith("dashboard/catalogue")
                || normalized.startsWith("dashboard/reports")
                || normalized.startsWith("dashboard/analytics")
                || normalized.startsWith("dashboard/dsar")
                || normalized.startsWith("dashboard/webhooks")
                || normalized.startsWith("dashboard/organizations/")
                || normalized.startsWith("dashboard/switch-org"));
    }

    /**
     * Aborts the request with HTTP 401 Unauthorized and a JSON error body.
     *
     * @param ctx the request context
     * @param message the error message to include in the response body
     */
    private static void abortUnauthorized(ContainerRequestContext ctx, String message) {
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
     * AuthorizationContext}.
     *
     * <p>The {@code isUserInRole()} method checks both legacy roles from the IdentityContext (for
     * backward compatibility with existing {@link com.koostack.cveil.rest.auth.Roles} checks) and
     * new permissions from the AuthorizationContext.
     */
    private record CveilSecurityContext(
            IdentityContext identity, AuthorizationContext authorization, boolean secure)
            implements SecurityContext {
        @Override
        public Principal getUserPrincipal() {
            return identity::subject;
        }

        @Override
        public boolean isUserInRole(String role) {
            // Check legacy roles from IdentityContext
            if (role != null && identity.roles() != null && identity.roles().contains(role)) {
                return true;
            }
            // Check DB-backed roles and permissions from AuthorizationContext
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
