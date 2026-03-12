package com.koostack.operio.rest.site;

import com.koostack.operio.core.cqrs.AbstractOrchestrator.OrchestratorException;
import com.koostack.operio.core.exception.OperioAuthException;
import com.koostack.operio.core.models.AuthResult;
import com.koostack.operio.platform.iam.jooq.AuthenticateCommandActuator;
import com.koostack.operio.platform.iam.jooq.LogoutCommandActuator;
import com.koostack.operio.rest.config.PlatformCqrsHolder;
import com.koostack.operio.rest.orchestrator.RegisterUserOrchestrator;
import com.koostack.operio.rest.util.AuthCookieHelper;
import com.koostack.operio.rest.util.TenantIdDeriver;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the public-facing Operio website pages and authenticated dashboard using Qute templates.
 *
 * <p>Public pages are unauthenticated (listed in {@code ApiKeyAuthFilter.isPublicPath}). The
 * dashboard uses HTTP-only cookie-based authentication managed by {@link AuthCookieHelper}.
 */
@Path("/")
public class SiteResource {

    private static final Logger LOG = LoggerFactory.getLogger(SiteResource.class);
    private static final String REALM = "operio";

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

    @Inject
    @io.quarkus.qute.Location("org-detail.html")
    Template orgDetail;

    @Inject PlatformCqrsHolder cqrs;

    @Context HttpHeaders httpHeaders;

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

            var orchestrator =
                    new RegisterUserOrchestrator(
                            cqrs.iamDispatcher(),
                            cqrs.coreDispatcher(),
                            cqrs.billingDispatcher(),
                            cqrs.isPermissionServiceAvailable() ? cqrs.permissionService() : null);

            var command =
                    new RegisterUserOrchestrator.RegisterUserCommand(
                            REALM,
                            username,
                            email,
                            fullName,
                            Set.of(), // KC roles empty — DB-backed roles are authoritative
                            false,
                            password,
                            tenantId);

            RegisterUserOrchestrator.RegisterUserResult result = orchestrator.execute(command);

            // Auto-login: authenticate with the credentials just used for registration
            try {
                AuthResult auth =
                        cqrs.iamDispatcher()
                                .dispatch(
                                        new AuthenticateCommandActuator.AuthenticateCommand(
                                                REALM, email, password));
                NewCookie[] cookies =
                        AuthCookieHelper.setAuthCookies(
                                auth.accessToken(), auth.refreshToken(), auth.expiresIn());
                return Response.seeOther(URI.create("/dashboard")).cookie(cookies).build();
            } catch (Exception loginEx) {
                LOG.warn("Auto-login after registration failed: {}", loginEx.getMessage());
                // Fallback: redirect to signup success page
                return Response.seeOther(
                                URI.create(
                                        "/signup/success?username="
                                                + URLEncoder.encode(
                                                        result.username(), StandardCharsets.UTF_8)
                                                + "&tenant="
                                                + URLEncoder.encode(
                                                        result.tenantId(), StandardCharsets.UTF_8)))
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
        } catch (OperioAuthException e) {
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
        String cookieHeader = httpHeaders.getHeaderString("Cookie");
        String accessToken =
                AuthCookieHelper.readCookie(cookieHeader, AuthCookieHelper.ACCESS_COOKIE);

        if (accessToken == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        // Decode JWT payload for display (without cryptographic verification —
        // the token was set by this server via HttpOnly cookie)
        String payload = AuthCookieHelper.decodeJwtPayload(accessToken);
        if (payload == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        // Extract claims from JWT payload JSON
        String username = extractJsonString(payload, "preferred_username");
        if (username == null) {
            username = extractJsonString(payload, "sub");
        }
        String email = extractJsonString(payload, "email");
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;

        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";

        return dashboard
                .data("username", username)
                .data("email", email)
                .data("tenantId", tenantId)
                .data("roles", resolveRolesDisplay(payload))
                .data("initial", initial)
                .data("activeNav", "overview");
    }

    @GET
    @Path("dashboard/account")
    @Produces(MediaType.TEXT_HTML)
    public Object accountPage() {
        String cookieHeader = httpHeaders.getHeaderString("Cookie");
        String accessToken =
                AuthCookieHelper.readCookie(cookieHeader, AuthCookieHelper.ACCESS_COOKIE);

        if (accessToken == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String payload = AuthCookieHelper.decodeJwtPayload(accessToken);
        if (payload == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String username = extractJsonString(payload, "preferred_username");
        if (username == null) username = extractJsonString(payload, "sub");
        String email = extractJsonString(payload, "email");
        String fullName = extractJsonString(payload, "name");
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;
        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";

        // Resolve roles from DB permissions framework
        String rolesDisplay = resolveRolesDisplay(payload);

        return account.data("username", username)
                .data("email", email)
                .data("fullName", fullName)
                .data("tenantId", tenantId)
                .data("roles", rolesDisplay)
                .data("initial", initial)
                .data("activeNav", "account");
    }

    @GET
    @Path("dashboard/governance")
    @Produces(MediaType.TEXT_HTML)
    public Object governancePage() {
        String cookieHeader = httpHeaders.getHeaderString("Cookie");
        String accessToken =
                AuthCookieHelper.readCookie(cookieHeader, AuthCookieHelper.ACCESS_COOKIE);

        if (accessToken == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String payload = AuthCookieHelper.decodeJwtPayload(accessToken);
        if (payload == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String username = extractJsonString(payload, "preferred_username");
        if (username == null) username = extractJsonString(payload, "sub");
        String email = extractJsonString(payload, "email");
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;
        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";

        return governance
                .data("username", username)
                .data("email", email)
                .data("tenantId", tenantId)
                .data("roles", resolveRolesDisplay(payload))
                .data("initial", initial)
                .data("activeNav", "governance");
    }

    @GET
    @Path("dashboard/consent")
    @Produces(MediaType.TEXT_HTML)
    public Object consentPage() {
        String cookieHeader = httpHeaders.getHeaderString("Cookie");
        String accessToken =
                AuthCookieHelper.readCookie(cookieHeader, AuthCookieHelper.ACCESS_COOKIE);

        if (accessToken == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String payload = AuthCookieHelper.decodeJwtPayload(accessToken);
        if (payload == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String username = extractJsonString(payload, "preferred_username");
        if (username == null) username = extractJsonString(payload, "sub");
        String email = extractJsonString(payload, "email");
        String tenantId = username != null ? TenantIdDeriver.derive(username) : null;
        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";

        return consent.data("username", username)
                .data("email", email)
                .data("tenantId", tenantId)
                .data("roles", resolveRolesDisplay(payload))
                .data("initial", initial)
                .data("activeNav", "consent");
    }

    @GET
    @Path("dashboard/organizations/{orgId}")
    @Produces(MediaType.TEXT_HTML)
    public Object orgDetailPage(@PathParam("orgId") String orgId) {
        String cookieHeader = httpHeaders.getHeaderString("Cookie");
        String accessToken =
                AuthCookieHelper.readCookie(cookieHeader, AuthCookieHelper.ACCESS_COOKIE);

        if (accessToken == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String payload = AuthCookieHelper.decodeJwtPayload(accessToken);
        if (payload == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        String username = extractJsonString(payload, "preferred_username");
        if (username == null) username = extractJsonString(payload, "sub");
        String email = extractJsonString(payload, "email");
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
                                        new com.koostack.operio.platform.iam.jooq
                                                .GetOrganizationQueryActuator.GetOrganizationQuery(
                                                orgId));
                if (result
                        instanceof
                        com.koostack.operio.core.platform.iam.IamQueryService.OrganizationInfo
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
                .data("roles", resolveRolesDisplay(payload))
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
        return Response.seeOther(URI.create("/login")).cookie(cleared).build();
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
