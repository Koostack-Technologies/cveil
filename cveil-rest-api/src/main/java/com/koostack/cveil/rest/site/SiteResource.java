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
import com.koostack.cveil.rest.onboarding.IndustryPreset;
import com.koostack.cveil.rest.onboarding.OnboardingStateHolder;
import com.koostack.cveil.rest.orchestrator.RegisterOrganizationOrchestrator;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

    @Inject
    @io.quarkus.qute.Location("onboarding-workspace.html")
    Template onboardingWorkspace;

    @Inject
    @io.quarkus.qute.Location("onboarding-industry.html")
    Template onboardingIndustry;

    @Inject
    @io.quarkus.qute.Location("onboarding-compliance.html")
    Template onboardingCompliance;

    @Inject
    @io.quarkus.qute.Location("onboarding-complete.html")
    Template onboardingComplete;

    @Inject PlatformCqrsHolder cqrs;

    @Inject ProvisioningTracker provisioningTracker;

    @Inject OnboardingStateHolder onboardingStateHolder;

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
        List<?> tiers;
        try {
            tiers =
                    cqrs.planQueryDispatcher()
                            .dispatch(
                                    new com.koostack.cveil.platform.plan.jooq
                                            .ListPricingTiersQueryActuator.ListPricingTiersQuery());
        } catch (IllegalStateException e) {
            tiers = List.of();
        }
        return plans.data("tiers", tiers);
    }

    @GET
    @Path("signup")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance signupPage(@QueryParam("plan") String plan) {
        return signup.data("error", null).data("plan", plan);
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
            @FormParam("confirmPassword") String confirmPassword,
            @FormParam("plan") String plan) {

        LOG.debug("POST /signup username={}, email={}, plan={}", username, email, plan);

        if (username == null
                || username.isBlank()
                || email == null
                || email.isBlank()
                || password == null
                || password.isBlank()) {
            return signup.data("error", "Username, email, and password are required.")
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName)
                    .data("plan", plan);
        }

        if (!password.equals(confirmPassword)) {
            return signup.data("error", "Passwords do not match.")
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName)
                    .data("plan", plan);
        }

        if (!cqrs.isCqrsAvailable()) {
            return signup.data(
                            "error",
                            "Registration is temporarily unavailable. Please try again later.")
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName)
                    .data("plan", plan);
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

            // ── Auto-login and redirect to onboarding wizard ───────────────
            // Deferred provisioning (governance + billing) runs after the user
            // completes the onboarding wizard and selects their compliance profile.
            try {
                AuthResult auth =
                        cqrs.iamDispatcher()
                                .dispatch(
                                        new AuthenticateCommandActuator.AuthenticateCommand(
                                                REALM, email, password));
                NewCookie[] cookies =
                        AuthCookieHelper.setAuthCookies(
                                auth.accessToken(), auth.refreshToken(), auth.expiresIn());
                String onboardingUri =
                        plan != null && !plan.isBlank()
                                ? "/onboarding?plan=" + plan
                                : "/onboarding";
                return Response.seeOther(URI.create(onboardingUri)).cookie(cookies).build();
            } catch (Exception loginEx) {
                LOG.warn("Auto-login after registration failed: {}", loginEx.getMessage());
                return Response.seeOther(URI.create("/login")).build();
            }
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid username for tenant derivation: {}", e.getMessage());
            return signup.data(
                            "error",
                            "Invalid username. Use letters and numbers only (e.g. janedoe).")
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName)
                    .data("plan", plan);
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
                    .data("fullName", fullName)
                    .data("plan", plan);
        } catch (Exception e) {
            LOG.error("Unexpected error during registration: {}", e.getMessage(), e);
            return signup.data("error", "An unexpected error occurred. Please try again.")
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName)
                    .data("plan", plan);
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
    // Onboarding Wizard (multi-step setup after registration)
    // -----------------------------------------------------------------------

    /** Step 1: Name your workspace and choose personal vs team. */
    @GET
    @Path("onboarding")
    @Produces(MediaType.TEXT_HTML)
    public Object onboardingStep1(@QueryParam("plan") String plan) {
        UserContext uc = resolveUserContext();
        if (uc == null) return Response.seeOther(URI.create("/login")).build();

        String tenantId = TenantIdDeriver.derive(uc.username());
        var state = onboardingStateHolder.start(tenantId);
        if (plan != null && !plan.isBlank()) {
            state.setPlan(plan);
        }
        return onboardingWorkspace.data("error", null).data("workspaceName", "");
    }

    @POST
    @Path("onboarding")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Object processOnboardingStep1(
            @FormParam("workspaceName") String workspaceName, @FormParam("isTeam") String isTeam) {

        UserContext uc = resolveUserContext();
        if (uc == null) return Response.seeOther(URI.create("/login")).build();

        String tenantId = TenantIdDeriver.derive(uc.username());
        var state = onboardingStateHolder.get(tenantId);
        if (state == null) state = onboardingStateHolder.start(tenantId);

        String trimmedName = workspaceName != null ? workspaceName.trim() : "My Workspace";
        if (trimmedName.isEmpty()) trimmedName = "My Workspace";
        state.setWorkspaceName(trimmedName);
        state.setTeam("on".equals(isTeam) || "true".equals(isTeam));

        // If the user wants a team workspace, create the organization now so we
        // have a TenantContext for all subsequent governance seed operations.
        if (state.isTeam()) {
            if (!cqrs.isCqrsAvailable()) {
                return onboardingWorkspace
                        .data("error", "Organization creation is temporarily unavailable.")
                        .data("workspaceName", trimmedName);
            }
            try {
                String orgName =
                        TenantIdDeriver.derive(
                                trimmedName.toLowerCase().replaceAll("[^a-z0-9]", ""));
                String kcUserId = resolveKeycloakUserId(uc);

                var orchestrator =
                        new RegisterOrganizationOrchestrator(
                                cqrs.iamDispatcher(),
                                cqrs.coreDispatcher(),
                                cqrs.billingDispatcher(),
                                cqrs.isPermissionServiceAvailable()
                                        ? cqrs.permissionService()
                                        : null,
                                consentDispatcher);

                var orgCommand =
                        new RegisterOrganizationOrchestrator.RegisterOrgCommand(
                                REALM, orgName, trimmedName, kcUserId, uc.username(), "free");

                var orgResult = orchestrator.execute(orgCommand);
                state.setOrgTenantId(orgResult.orgName());
                LOG.info(
                        "Organization '{}' created during onboarding for user {}",
                        orgResult.orgName(),
                        uc.username());
            } catch (OrchestratorException e) {
                LOG.error(
                        "Org creation in onboarding failed at step '{}': {}",
                        e.failedStep(),
                        e.getMessage(),
                        e);
                return onboardingWorkspace
                        .data(
                                "error",
                                "Could not create organization. Please try again or uncheck"
                                        + " the team option.")
                        .data("workspaceName", trimmedName);
            } catch (Exception e) {
                LOG.error("Unexpected error creating org in onboarding: {}", e.getMessage(), e);
                return onboardingWorkspace
                        .data("error", "An unexpected error occurred. Please try again.")
                        .data("workspaceName", trimmedName);
            }
        }

        return Response.seeOther(URI.create("/onboarding/industry")).build();
    }

    /** Step 2: Choose your industry preset. */
    @GET
    @Path("onboarding/industry")
    @Produces(MediaType.TEXT_HTML)
    public Object onboardingStep2() {
        UserContext uc = resolveUserContext();
        if (uc == null) return Response.seeOther(URI.create("/login")).build();

        return onboardingIndustry.data("presets", IndustryPreset.values());
    }

    @POST
    @Path("onboarding/industry")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Object processOnboardingStep2(@FormParam("industry") String industry) {
        UserContext uc = resolveUserContext();
        if (uc == null) return Response.seeOther(URI.create("/login")).build();

        String tenantId = TenantIdDeriver.derive(uc.username());
        var state = onboardingStateHolder.get(tenantId);
        if (state == null) return Response.seeOther(URI.create("/onboarding")).build();

        try {
            IndustryPreset preset = IndustryPreset.valueOf(industry);
            state.setIndustryPreset(preset);
        } catch (IllegalArgumentException e) {
            return Response.seeOther(URI.create("/onboarding/industry")).build();
        }

        return Response.seeOther(URI.create("/onboarding/compliance")).build();
    }

    /** Step 3: Review and customize compliance profile (jurisdictions, entities). */
    @GET
    @Path("onboarding/compliance")
    @Produces(MediaType.TEXT_HTML)
    public Object onboardingStep3() {
        UserContext uc = resolveUserContext();
        if (uc == null) return Response.seeOther(URI.create("/login")).build();

        String tenantId = TenantIdDeriver.derive(uc.username());
        var state = onboardingStateHolder.get(tenantId);
        if (state == null) return Response.seeOther(URI.create("/onboarding")).build();
        if (state.industryPreset() == null) {
            return Response.seeOther(URI.create("/onboarding/industry")).build();
        }

        return renderCompliancePage(state, null);
    }

    @POST
    @Path("onboarding/compliance")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Object processOnboardingStep3(
            @FormParam("jurisdictions") List<String> jurisdictions,
            @FormParam("entities") List<String> entities) {

        UserContext uc = resolveUserContext();
        if (uc == null) return Response.seeOther(URI.create("/login")).build();

        String tenantId = TenantIdDeriver.derive(uc.username());
        var state = onboardingStateHolder.get(tenantId);
        if (state == null) return Response.seeOther(URI.create("/onboarding")).build();

        // Guard: prevent double-submit — if provisioning is already running, redirect
        var existingStatus = provisioningTracker.getStatus(tenantId);
        if (existingStatus != null) {
            return Response.seeOther(URI.create("/onboarding/complete")).build();
        }

        if (jurisdictions == null || jurisdictions.isEmpty()) {
            return renderCompliancePage(state, "Please select at least one jurisdiction.");
        }

        int tierLimit = resolveTierLimit();
        if (tierLimit > 0 && jurisdictions.size() > tierLimit) {
            return renderCompliancePage(
                    state,
                    "Your plan allows "
                            + tierLimit
                            + " jurisdictions. Please deselect "
                            + (jurisdictions.size() - tierLimit)
                            + " to continue.");
        }

        state.setSelectedJurisdictions(jurisdictions);
        state.setSelectedEntities(entities != null ? entities : List.of());

        Set<String> selectedRegulations = IndustryPreset.deriveRegulations(jurisdictions);
        Set<String> purposeIds = new LinkedHashSet<>(state.industryPreset().purposes());

        // Always provision under the personal tenantId. The dashboard and API auth
        // resolve the tenant from the JWT (personal), so governance + billing data
        // must live there. The org created in Step 1 reserves the namespace but does
        // not change the data-scoping until org context switching is implemented.
        provisioningTracker.start(tenantId, DeferredProvisioningTask.totalSteps());
        provisioningExecutor.submit(
                new DeferredProvisioningTask(
                        tenantId,
                        uc.email(),
                        state.plan(),
                        consentDispatcher,
                        cqrs.billingDispatcher(),
                        cqrs.paymentProcessorService(),
                        provisioningTracker,
                        new LinkedHashSet<>(jurisdictions),
                        selectedRegulations,
                        purposeIds,
                        new LinkedHashSet<>(entities != null ? entities : List.of())));

        onboardingStateHolder.complete(tenantId);

        return Response.seeOther(URI.create("/onboarding/complete")).build();
    }

    /** Step 4: Progress page — polls /onboarding/status until seeding completes. */
    @GET
    @Path("onboarding/complete")
    @Produces(MediaType.TEXT_HTML)
    public Object onboardingCompletePage() {
        UserContext uc = resolveUserContext();
        if (uc == null) return Response.seeOther(URI.create("/login")).build();

        String tenantId = TenantIdDeriver.derive(uc.username());
        var status = resolveProvisioningStatus(tenantId);
        if (status == null || status.status() == ProvisioningTracker.Status.COMPLETED) {
            return Response.seeOther(URI.create("/dashboard")).build();
        }

        return onboardingComplete.instance();
    }

    /** JSON status endpoint for the onboarding progress page. */
    @GET
    @Path("onboarding/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response onboardingStatus() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Not authenticated"))
                    .build();
        }

        String tenantId = TenantIdDeriver.derive(uc.username());
        ProvisioningTracker.ProvisioningStatus status = resolveProvisioningStatus(tenantId);

        if (status == null) {
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

    // ── Onboarding helpers ────────────────────────────────────────────────

    private int resolveTierLimit() {
        try {
            var planInfo = cqrs.planQueryService().getPlan("free");
            if (planInfo != null) return planInfo.jurisdictionLimit();
        } catch (Exception e) {
            LOG.warn("Failed to resolve tier limit, using default: {}", e.getMessage());
        }
        return 3;
    }

    private Object renderCompliancePage(OnboardingStateHolder.OnboardingState state, String error) {

        IndustryPreset preset = state.industryPreset();
        int tierLimit = resolveTierLimit();

        var preChecked = new HashSet<>(preset.preCheckedJurisdictions(tierLimit));
        var locked = new HashSet<>(preset.lockedJurisdictions(tierLimit));

        List<String> showJurisdictions =
                preset == IndustryPreset.GENERAL
                        ? IndustryPreset.allJurisdictionIds()
                        : preset.fullJurisdictionSet();

        var jurisdictions = new ArrayList<Map<String, Object>>();
        for (String jurId : showJurisdictions) {
            jurisdictions.add(
                    Map.of(
                            "id", jurId,
                            "name", IndustryPreset.jurisdictionDisplayName(jurId),
                            "description", IndustryPreset.jurisdictionDescription(jurId),
                            "checked", preChecked.contains(jurId),
                            "locked", locked.contains(jurId)));
        }

        var regulations = new ArrayList<Map<String, Object>>();
        for (String regId :
                IndustryPreset.deriveRegulations(
                        showJurisdictions.isEmpty()
                                ? IndustryPreset.allJurisdictionIds()
                                : showJurisdictions)) {
            regulations.add(Map.of("name", regId, "jurisdictions", ""));
        }

        var entities = new ArrayList<Map<String, Object>>();
        for (String entityId : preset.entities()) {
            entities.add(Map.of("id", entityId, "checked", true));
        }

        int initialChecked = preChecked.size();
        int tierPercent = tierLimit > 0 ? Math.min(100, (initialChecked * 100) / tierLimit) : 0;

        return onboardingCompliance
                .data("presetName", preset.displayName())
                .data("jurisdictions", jurisdictions)
                .data("regulations", regulations)
                .data("entities", entities)
                .data("tierLimit", tierLimit)
                .data("initialChecked", initialChecked)
                .data("tierPercent", tierPercent)
                .data("error", error);
    }

    /**
     * Resolves the provisioning status for a tenant. All provisioning is now keyed by the personal
     * tenantId, so a direct lookup suffices.
     */
    private ProvisioningTracker.ProvisioningStatus resolveProvisioningStatus(String tenantId) {
        return provisioningTracker.getStatus(tenantId);
    }

    /** Extracts the Keycloak user ID (sub claim) from the resolved user context. */
    private String resolveKeycloakUserId(UserContext uc) {
        if (uc.jwtPayload() != null) {
            String sub = extractJsonString(uc.jwtPayload(), "sub");
            if (sub != null) return sub;
        }
        return uc.username();
    }

    /**
     * Checks whether the tenant has completed the onboarding flow.
     *
     * <p>Uses the existence of a billing subscription as a durable signal. The subscription is
     * created as the final step of {@link DeferredProvisioningTask}, so its presence means the full
     * provisioning pipeline (governance seed + billing + Stripe + subscription) completed. Unlike
     * the in-memory {@link ProvisioningTracker}, this survives server restarts.
     */
    private boolean hasCompletedOnboarding(String tenantId) {
        try {
            var billingQs = cqrs.billingQueryService();
            if (billingQs == null) {
                return true;
            }
            return billingQs.getSubscription(tenantId) != null;
        } catch (Exception e) {
            LOG.debug("Could not check subscription for {}: {}", tenantId, e.getMessage());
            return true;
        }
    }

    /**
     * Checks if the dashboard should be blocked because onboarding is incomplete. Returns a
     * redirect Response if the user should be sent back to onboarding or the progress page; returns
     * {@code null} if the user may proceed to the dashboard.
     */
    private Response checkOnboardingGuard(String tenantId) {
        if (tenantId == null) return null;
        ProvisioningTracker.ProvisioningStatus provStatus = provisioningTracker.getStatus(tenantId);
        if (provStatus != null && provStatus.status() == ProvisioningTracker.Status.IN_PROGRESS) {
            return Response.seeOther(URI.create("/onboarding/complete")).build();
        }
        if (!hasCompletedOnboarding(tenantId)) {
            return Response.seeOther(URI.create("/onboarding")).build();
        }
        return null;
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

        // Guard: redirect if onboarding is in progress or never completed
        Response onboardingRedirect = checkOnboardingGuard(tenantId);
        if (onboardingRedirect != null) return onboardingRedirect;

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

        Response onboardingRedirect = checkOnboardingGuard(tenantId);
        if (onboardingRedirect != null) return onboardingRedirect;

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

        Response onboardingRedirect = checkOnboardingGuard(tenantId);
        if (onboardingRedirect != null) return onboardingRedirect;

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

        Response onboardingRedirect = checkOnboardingGuard(tenantId);
        if (onboardingRedirect != null) return onboardingRedirect;

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

        Response onboardingRedirect = checkOnboardingGuard(tenantId);
        if (onboardingRedirect != null) return onboardingRedirect;

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

        Response onboardingRedirect = checkOnboardingGuard(tenantId);
        if (onboardingRedirect != null) return onboardingRedirect;

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
}
