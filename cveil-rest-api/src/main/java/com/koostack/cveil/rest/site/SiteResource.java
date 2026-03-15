package com.koostack.cveil.rest.site;

import com.koostack.cveil.core.cqrs.AbstractOrchestrator.OrchestratorException;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.exception.CveilAuthException;
import com.koostack.cveil.core.models.AuthResult;
import com.koostack.cveil.platform.iam.jooq.AuthenticateCommandActuator;
import com.koostack.cveil.platform.iam.jooq.LogoutCommandActuator;
import com.koostack.cveil.rest.config.PlatformCqrsHolder;
import com.koostack.cveil.rest.lifecycle.DeferredProvisioningTask;
import com.koostack.cveil.rest.lifecycle.ProvisioningTracker;
import com.koostack.cveil.rest.orchestrator.RegisterUserSyncOrchestrator;
import com.koostack.cveil.rest.util.AuthCookieHelper;
import com.koostack.cveil.rest.util.TenantIdDeriver;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the public-facing Cveil website pages and authenticated dashboard using Qute templates.
 *
 * <p>Public pages are unauthenticated (listed in {@code ApiKeyAuthFilter.isPublicPath}). The
 * dashboard uses HTTP-only cookie-based authentication managed by {@link AuthCookieHelper}.
 */
@Path("/")
public class SiteResource {

    private static final Logger LOG = LoggerFactory.getLogger(SiteResource.class);
    private static final String REALM = "cveil";

    /** Cache-buster token — changes on every application restart / redeploy. */
    private static final String BUILD_VERSION = Long.toHexString(System.currentTimeMillis());

    @Inject Template index;

    @Inject Template docs;

    @Inject Template plans;

    @Inject Template signup;

    @Inject Template signupSuccess;

    @Inject Template login;

    @Inject Template dashboard;

    @Inject Template account;

    @Inject Template governance;

    @Inject Template consent;

    @Inject Template catalogue;

    @Inject Template billing;

    @Inject Template setup;

    @Inject
    @io.quarkus.qute.Location("org-detail.html")
    Template orgDetail;

    @Inject PlatformCqrsHolder cqrs;

    @Inject ProvisioningTracker provisioningTracker;

    @Inject
    @Named("consentDispatcher")
    CommandDispatcher consentDispatcher;

    @Context HttpHeaders httpHeaders;

    /** Virtual-thread executor for deferred provisioning tasks. */
    private final ExecutorService provisioningExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance landing() {
        return index.instance();
    }

    @GET
    @Path("docs")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance documentation() {
        return docs.instance();
    }

    @GET
    @Path("plans")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance pricing() {
        return plans.data("tiers", pricingTiers());
    }

    @GET
    @Path("signup")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance signupPage() {
        return signup.data("error", null);
    }

    @POST
    @Path("signup")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Object processSignup(
            @FormParam("username") String username,
            @FormParam("email") String email,
            @FormParam("fullName") String fullName,
            @FormParam("password") String password,
            @FormParam("confirmPassword") String confirmPassword) {

        LOG.debug("POST /signup username={}, email={}", username, email);

        if (username == null
                || username.isBlank()
                || email == null
                || email.isBlank()
                || password == null
                || password.isBlank()) {
            return signup.data("error", "Username, email, and password are required.")
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName);
        }

        if (!password.equals(confirmPassword)) {
            return signup.data("error", "Passwords do not match.")
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName);
        }

        if (!cqrs.isCqrsAvailable()) {
            return signup.data(
                            "error",
                            "Registration is temporarily unavailable. Please try again later.")
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName);
        }

        try {
            String tenantId = TenantIdDeriver.derive(username);

            // ── Fast synchronous path (steps 1–4: ~1–2s) ──────────────────
            var syncOrchestrator =
                    new RegisterUserSyncOrchestrator(
                            cqrs.iamDispatcher(),
                            cqrs.coreDispatcher(),
                            cqrs.isPermissionServiceAvailable() ? cqrs.permissionService() : null);

            var syncCommand =
                    new RegisterUserSyncOrchestrator.RegisterUserSyncCommand(
                            REALM, username, email, fullName, null, Set.of(), false, password,
                            tenantId);

            syncOrchestrator.execute(syncCommand);

            // ── Kick off deferred provisioning (steps 5–7) in background ───
            provisioningTracker.start(tenantId, DeferredProvisioningTask.totalSteps());
            provisioningExecutor.submit(
                    new DeferredProvisioningTask(
                            tenantId,
                            email,
                            "free",
                            consentDispatcher,
                            cqrs.billingDispatcher(),
                            cqrs.paymentProcessorService(),
                            provisioningTracker));

            // ── Auto-login and redirect to setup loading page ──────────────
            try {
                AuthResult auth =
                        cqrs.iamDispatcher()
                                .dispatch(
                                        new AuthenticateCommandActuator.AuthenticateCommand(
                                                REALM, email, password));
                NewCookie[] cookies =
                        AuthCookieHelper.setAuthCookies(
                                auth.accessToken(), auth.refreshToken(), auth.expiresIn());
                return Response.seeOther(URI.create("/setup")).cookie(cookies).build();
            } catch (Exception loginEx) {
                LOG.warn("Auto-login after registration failed: {}", loginEx.getMessage());
                return Response.seeOther(
                                URI.create(
                                        "/setup?username="
                                                + URLEncoder.encode(
                                                        username, StandardCharsets.UTF_8)
                                                + "&tenant="
                                                + URLEncoder.encode(
                                                        tenantId, StandardCharsets.UTF_8)))
                        .build();
            }
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid username for tenant derivation: {}", e.getMessage());
            return signup.data(
                            "error",
                            "Invalid username. Use letters and numbers only (e.g. janedoe).")
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName);
        } catch (OrchestratorException e) {
            LOG.error(
                    "User registration saga failed at step '{}' ({}/{} completed): {}",
                    e.failedStep(),
                    e.completedSteps(),
                    e.totalSteps(),
                    e.getMessage(),
                    e);
            return signup.data("error", "Registration failed. Please try again or contact support.")
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName);
        } catch (Exception e) {
            LOG.error("Unexpected error during registration: {}", e.getMessage(), e);
            return signup.data("error", "An unexpected error occurred. Please try again.")
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName);
        }
    }

    @GET
    @Path("signup/success")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance signupSuccessPage(
            @QueryParam("username") String username, @QueryParam("tenant") String tenantId) {
        return signupSuccess.data("username", username).data("tenantId", tenantId);
    }

    // -----------------------------------------------------------------------
    // Workspace setup (loading screen during deferred provisioning)
    // -----------------------------------------------------------------------

    @GET
    @Path("setup")
    @Produces(MediaType.TEXT_HTML)
    public Object setupPage(
            @QueryParam("username") String qpUsername, @QueryParam("tenant") String qpTenantId) {
        // Try cookie-based auth first
        String cookieHeader = httpHeaders.getHeaderString("Cookie");
        String accessToken =
                AuthCookieHelper.readCookie(cookieHeader, AuthCookieHelper.ACCESS_COOKIE);

        String username = qpUsername;
        String tenantId = qpTenantId;

        if (accessToken != null) {
            String payload = AuthCookieHelper.decodeJwtPayload(accessToken);
            if (payload != null) {
                String jwtUsername = extractJsonString(payload, "preferred_username");
                if (jwtUsername == null) jwtUsername = extractJsonString(payload, "sub");
                if (jwtUsername != null) username = jwtUsername;
                if (username != null) {
                    try {
                        tenantId = TenantIdDeriver.derive(username);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // If provisioning is already done (or was never tracked), go straight to dashboard
        if (tenantId != null) {
            ProvisioningTracker.ProvisioningStatus status = provisioningTracker.getStatus(tenantId);
            if (status == null || status.status() == ProvisioningTracker.Status.COMPLETED) {
                return Response.seeOther(URI.create("/dashboard")).build();
            }
        }

        return setup.data("username", username).data("tenantId", tenantId);
    }

    @GET
    @Path("setup/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response setupStatus() {
        String cookieHeader = httpHeaders.getHeaderString("Cookie");
        String accessToken =
                AuthCookieHelper.readCookie(cookieHeader, AuthCookieHelper.ACCESS_COOKIE);

        if (accessToken == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Not authenticated"))
                    .build();
        }

        String payload = AuthCookieHelper.decodeJwtPayload(accessToken);
        if (payload == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Invalid token"))
                    .build();
        }

        String username = extractJsonString(payload, "preferred_username");
        if (username == null) username = extractJsonString(payload, "sub");
        if (username == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Cannot determine tenant"))
                    .build();
        }

        String tenantId;
        try {
            tenantId = TenantIdDeriver.derive(username);
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid tenant ID"))
                    .build();
        }

        ProvisioningTracker.ProvisioningStatus status = provisioningTracker.getStatus(tenantId);

        if (status == null) {
            // No tracker entry → provisioning already completed (or pre-existing tenant)
            return Response.ok(Map.of("status", "COMPLETED")).build();
        }

        return Response.ok(
                        Map.of(
                                "status", status.status().name(),
                                "currentStep",
                                        status.currentStep() != null ? status.currentStep() : "",
                                "completedSteps", status.completedSteps(),
                                "totalSteps", status.totalSteps(),
                                "errorMessage",
                                        status.errorMessage() != null ? status.errorMessage() : ""))
                .build();
    }

    // -----------------------------------------------------------------------
    // Login
    // -----------------------------------------------------------------------

    @GET
    @Path("login")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance loginPage() {
        return login.data("error", null).data("identifier", null);
    }

    @POST
    @Path("login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Object processLogin(
            @FormParam("identifier") String identifier, @FormParam("password") String password) {

        LOG.debug("POST /login identifier={}", identifier);

        if (identifier == null || identifier.isBlank() || password == null || password.isBlank()) {
            return login.data("error", "Email/username and password are required.")
                    .data("identifier", identifier);
        }

        if (!cqrs.isCqrsAvailable()) {
            return login.data(
                            "error",
                            "Authentication is temporarily unavailable. Please try again later.")
                    .data("identifier", identifier);
        }

        try {
            AuthResult result =
                    cqrs.iamDispatcher()
                            .dispatch(
                                    new AuthenticateCommandActuator.AuthenticateCommand(
                                            REALM, identifier, password));
            NewCookie[] cookies =
                    AuthCookieHelper.setAuthCookies(
                            result.accessToken(), result.refreshToken(), result.expiresIn());
            return Response.seeOther(URI.create("/dashboard")).cookie(cookies).build();
        } catch (CveilAuthException e) {
            LOG.debug("Login failed for identifier={}: {}", identifier, e.getMessage());
            return login.data("error", "Invalid email/username or password.")
                    .data("identifier", identifier);
        } catch (Exception e) {
            LOG.error("Unexpected error during login: {}", e.getMessage(), e);
            return login.data("error", "An unexpected error occurred. Please try again.")
                    .data("identifier", identifier);
        }
    }

    // -----------------------------------------------------------------------
    // Dashboard
    // -----------------------------------------------------------------------

    @GET
    @Path("dashboard")
    @Produces(MediaType.TEXT_HTML)
    public Object dashboardPage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String username = uc.username();
        String email = uc.email();
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;

        // Guard: redirect to setup page if workspace provisioning is still in progress
        if (tenantId != null) {
            ProvisioningTracker.ProvisioningStatus provStatus =
                    provisioningTracker.getStatus(tenantId);
            if (provStatus != null
                    && provStatus.status() == ProvisioningTracker.Status.IN_PROGRESS) {
                return Response.seeOther(URI.create("/setup")).build();
            }
        }

        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";

        return dashboard
                .data("username", username)
                .data("email", email)
                .data("tenantId", tenantId)
                .data(
                        "roles",
                        uc.jwtPayload() != null ? resolveRolesDisplay(uc.jwtPayload()) : "member")
                .data("initial", initial)
                .data("activeNav", "overview");
    }

    @GET
    @Path("dashboard/account")
    @Produces(MediaType.TEXT_HTML)
    public Object accountPage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String username = uc.username();
        String email = uc.email();
        String fullName =
                uc.jwtPayload() != null ? extractJsonString(uc.jwtPayload(), "name") : null;
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;
        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";

        String rolesDisplay =
                uc.jwtPayload() != null ? resolveRolesDisplay(uc.jwtPayload()) : "member";

        return account.data("username", username)
                .data("email", email)
                .data("fullName", fullName)
                .data("tenantId", tenantId)
                .data("roles", rolesDisplay)
                .data("initial", initial)
                .data("activeNav", "account");
    }

    @GET
    @Path("dashboard/billing")
    @Produces(MediaType.TEXT_HTML)
    public Object billingPage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String username = uc.username();
        String email = uc.email();
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;
        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";

        return billing.data("username", username)
                .data("email", email)
                .data("tenantId", tenantId)
                .data(
                        "roles",
                        uc.jwtPayload() != null ? resolveRolesDisplay(uc.jwtPayload()) : "member")
                .data("initial", initial)
                .data("activeNav", "billing")
                .data("v", BUILD_VERSION);
    }

    @GET
    @Path("dashboard/governance")
    @Produces(MediaType.TEXT_HTML)
    public Object governancePage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String username = uc.username();
        String email = uc.email();
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;
        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";

        return governance
                .data("username", username)
                .data("email", email)
                .data("tenantId", tenantId)
                .data(
                        "roles",
                        uc.jwtPayload() != null ? resolveRolesDisplay(uc.jwtPayload()) : "member")
                .data("initial", initial)
                .data("activeNav", "governance")
                .data("v", BUILD_VERSION);
    }

    @GET
    @Path("dashboard/catalogue")
    @Produces(MediaType.TEXT_HTML)
    public Object cataloguePage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String username = uc.username();
        String email = uc.email();
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;
        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";

        return catalogue
                .data("username", username)
                .data("email", email)
                .data("tenantId", tenantId)
                .data(
                        "roles",
                        uc.jwtPayload() != null ? resolveRolesDisplay(uc.jwtPayload()) : "member")
                .data("initial", initial)
                .data("activeNav", "catalogue")
                .data("v", BUILD_VERSION);
    }

    @GET
    @Path("dashboard/consent")
    @Produces(MediaType.TEXT_HTML)
    public Object consentPage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String username = uc.username();
        String email = uc.email();
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;
        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";

        return consent.data("username", username)
                .data("email", email)
                .data("tenantId", tenantId)
                .data(
                        "roles",
                        uc.jwtPayload() != null ? resolveRolesDisplay(uc.jwtPayload()) : "member")
                .data("initial", initial)
                .data("activeNav", "consent")
                .data("v", BUILD_VERSION);
    }

    @GET
    @Path("dashboard/organizations/{orgId}")
    @Produces(MediaType.TEXT_HTML)
    public Object orgDetailPage(@PathParam("orgId") String orgId) {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String username = uc.username();
        String email = uc.email();
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;
        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";

        // Look up org info for display
        String orgName = orgId;
        String orgDisplayName = orgId;
        if (cqrs.isCqrsAvailable()) {
            try {
                var result =
                        cqrs.iamQueryDispatcher()
                                .dispatch(
                                        new com.koostack.cveil.platform.iam.jooq
                                                .GetOrganizationQueryActuator.GetOrganizationQuery(
                                                orgId));
                if (result
                        instanceof
                        com.koostack.cveil.core.platform.iam.IamQueryService.OrganizationInfo
                        org) {
                    orgName = org.orgName();
                    orgDisplayName = org.displayName() != null ? org.displayName() : org.orgName();
                }
            } catch (Exception e) {
                LOG.warn("Failed to look up org {}: {}", orgId, e.getMessage());
            }
        }

        return orgDetail
                .data("orgId", orgId)
                .data("orgName", orgName)
                .data("orgDisplayName", orgDisplayName)
                .data("username", username)
                .data("email", email)
                .data("tenantId", tenantId)
                .data(
                        "roles",
                        uc.jwtPayload() != null ? resolveRolesDisplay(uc.jwtPayload()) : "member")
                .data("initial", initial)
                .data("activeNav", "account");
    }

    // -----------------------------------------------------------------------
    // Logout
    // -----------------------------------------------------------------------

    @POST
    @Path("logout")
    @Produces(MediaType.TEXT_HTML)
    public Response processLogout() {
        String cookieHeader = httpHeaders.getHeaderString("Cookie");
        String refreshToken =
                AuthCookieHelper.readCookie(cookieHeader, AuthCookieHelper.REFRESH_COOKIE);

        // Revoke the refresh token in Keycloak (best-effort)
        if (refreshToken != null && cqrs.isCqrsAvailable()) {
            try {
                cqrs.iamDispatcher()
                        .dispatch(new LogoutCommandActuator.LogoutCommand(REALM, refreshToken));
            } catch (Exception e) {
                LOG.warn("Token revocation during logout failed: {}", e.getMessage());
            }
        }

        NewCookie[] cleared = AuthCookieHelper.clearAuthCookies();
        // Also clear the DPoP session cookie (non-HttpOnly, set by JS login)
        NewCookie clearSession =
                new NewCookie.Builder(AuthCookieHelper.SESSION_COOKIE)
                        .value("")
                        .path("/")
                        .maxAge(0)
                        .sameSite(NewCookie.SameSite.LAX)
                        .build();
        return Response.seeOther(URI.create("/login"))
                .cookie(cleared[0], cleared[1], clearSession)
                .build();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Resolve the user's display roles from the DB permissions framework. */
    private String resolveRolesDisplay(String jwtPayload) {
        String sub = extractJsonString(jwtPayload, "sub");
        String username = extractJsonString(jwtPayload, "preferred_username");
        if (username == null) username = sub;
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;
        if (sub != null && tenantId != null && cqrs.isPermissionServiceAvailable()) {
            try {
                Set<String> dbRoles = cqrs.permissionService().resolveRoles(sub, tenantId);
                if (!dbRoles.isEmpty()) {
                    return String.join(", ", dbRoles);
                }
            } catch (Exception e) {
                LOG.debug("Could not resolve DB roles for template, falling back", e);
            }
        }
        return "member";
    }

    /**
     * Minimal JSON string extractor — avoids pulling in a JSON library for a single use case. Finds
     * {@code "key":"value"} in a flat JSON object.
     */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    /**
     * Lightweight container for user context needed by dashboard templates. Resolved from either
     * the {@code cveil_access} JWT cookie or the DPoP-flow {@code cveil_session} cookie.
     */
    record UserContext(String username, String email, String jwtPayload) {}

    /**
     * Resolves user context for server-rendered dashboard pages. Tries (in order):
     *
     * <ol>
     *   <li>{@code cveil_access} cookie — decode the JWT payload (pre-DPoP flow)
     *   <li>{@code cveil_session} cookie — lightweight base64 JSON set by DPoP login
     * </ol>
     *
     * @return resolved context, or {@code null} if the user is not authenticated
     */
    private UserContext resolveUserContext() {
        String cookieHeader = httpHeaders.getHeaderString("Cookie");

        // Path 1: traditional JWT cookie
        String accessToken =
                AuthCookieHelper.readCookie(cookieHeader, AuthCookieHelper.ACCESS_COOKIE);
        if (accessToken != null) {
            String payload = AuthCookieHelper.decodeJwtPayload(accessToken);
            if (payload != null) {
                String u = extractJsonString(payload, "preferred_username");
                if (u == null) u = extractJsonString(payload, "sub");
                String e = extractJsonString(payload, "email");
                return new UserContext(u, e, payload);
            }
        }

        // Path 2: lightweight DPoP session cookie
        String sessionJson = AuthCookieHelper.decodeSessionCookie(cookieHeader);
        if (sessionJson != null) {
            String u = extractJsonString(sessionJson, "u");
            String e = extractJsonString(sessionJson, "e");
            if (u != null) {
                return new UserContext(u, e, null);
            }
        }

        return null;
    }

    private List<Map<String, Object>> pricingTiers() {
        return List.of(
                Map.ofEntries(
                        Map.entry("name", "Starter"),
                        Map.entry("price", "$49"),
                        Map.entry("period", "/mo"),
                        Map.entry(
                                "description",
                                "For early-stage startups validating product-market fit."),
                        Map.entry("evaluations", "50,000"),
                        Map.entry("overage", "$0.002 / eval"),
                        Map.entry("masking", "Basic (email, phone)"),
                        Map.entry("auditRetention", "30 days"),
                        Map.entry("support", "Community + Docs"),
                        Map.entry("residency", "Shared US / EU"),
                        Map.entry("sso", false),
                        Map.entry("sla", "99.9%"),
                        Map.entry("cta", "Start Free Trial"),
                        Map.entry("highlighted", false)),
                Map.ofEntries(
                        Map.entry("name", "Pro"),
                        Map.entry("price", "$249"),
                        Map.entry("period", "/mo"),
                        Map.entry(
                                "description", "For growing teams that need compliance at scale."),
                        Map.entry("evaluations", "500,000"),
                        Map.entry("overage", "$0.001 / eval"),
                        Map.entry("masking", "Full field-level"),
                        Map.entry("auditRetention", "1 year"),
                        Map.entry("support", "Priority email · 24h SLA"),
                        Map.entry("residency", "Dedicated region"),
                        Map.entry("sso", true),
                        Map.entry("sla", "99.95%"),
                        Map.entry("cta", "Start Free Trial"),
                        Map.entry("highlighted", true)),
                Map.ofEntries(
                        Map.entry("name", "Enterprise"),
                        Map.entry("price", "Custom"),
                        Map.entry("period", ""),
                        Map.entry(
                                "description",
                                "For organizations with strict regulatory requirements."),
                        Map.entry("evaluations", "Unlimited"),
                        Map.entry("overage", "Custom"),
                        Map.entry("masking", "Full + custom rules"),
                        Map.entry("auditRetention", "Unlimited"),
                        Map.entry("support", "Dedicated CSM · Slack · 1h SLA"),
                        Map.entry("residency", "Custom / on-prem"),
                        Map.entry("sso", true),
                        Map.entry("sla", "99.99%"),
                        Map.entry("cta", "Contact Sales"),
                        Map.entry("highlighted", false)));
    }
}
