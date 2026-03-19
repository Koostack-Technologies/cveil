package com.koostack.cveil.rest.site;

import com.koostack.cveil.core.cqrs.AbstractOrchestrator.OrchestratorException;
import com.koostack.cveil.core.cqrs.CommandDispatcher;
import com.koostack.cveil.core.platform.iam.IamQueryService.OrganizationInfo;
import com.koostack.cveil.platform.iam.jooq.GetCurrentUserQueryActuator;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    @Inject Template reports;

    @Inject Template analytics;

    @Inject Template dsar;

    @Inject Template webhooks;

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
    @Produces({MediaType.TEXT_HTML, MediaType.APPLICATION_JSON})
    public Object processSignup(
            @FormParam("username") String username,
            @FormParam("email") String email,
            @FormParam("fullName") String fullName,
            @FormParam("password") String password,
            @FormParam("confirmPassword") String confirmPassword,
            @FormParam("plan") String plan) {

        LOG.debug("POST /signup username={}, email={}, plan={}", username, email, plan);

        String accept = httpHeaders.getHeaderString("Accept");
        boolean wantsJson = accept != null && accept.contains("application/json");

        if (username == null
                || username.isBlank()
                || email == null
                || email.isBlank()
                || password == null
                || password.isBlank()) {
            String msg = "Username, email, and password are required.";
            if (wantsJson) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(java.util.Map.of("error", msg))
                        .build();
            }
            return signup.data("error", msg)
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName)
                    .data("plan", plan);
        }

        if (!password.equals(confirmPassword)) {
            String msg = "Passwords do not match.";
            if (wantsJson) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(java.util.Map.of("error", msg))
                        .build();
            }
            return signup.data("error", msg)
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName)
                    .data("plan", plan);
        }

        if (!cqrs.isCqrsAvailable()) {
            String msg = "Registration is temporarily unavailable. Please try again later.";
            if (wantsJson) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(java.util.Map.of("error", msg))
                        .build();
            }
            return signup.data("error", msg)
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

            // ── JSON response: JS client will handle DPoP login separately ─
            if (wantsJson) {
                return Response.ok(
                                java.util.Map.of(
                                        "registered", true,
                                        "email", email,
                                        "username", username))
                        .type(MediaType.APPLICATION_JSON)
                        .build();
            }

            // ── HTML fallback: redirect to login (JS required for DPoP auth)
            return Response.seeOther(URI.create("/login")).build();
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid username for tenant derivation: {}", e.getMessage());
            String msg = "Invalid username. Use letters and numbers only (e.g. janedoe).";
            if (wantsJson) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(java.util.Map.of("error", msg))
                        .build();
            }
            return signup.data("error", msg)
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
            String userMessage = extractUserFriendlySignupError(e);
            if (wantsJson) {
                return Response.status(Response.Status.CONFLICT)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(java.util.Map.of("error", userMessage))
                        .build();
            }
            return signup.data("error", userMessage)
                    .data("username", username)
                    .data("email", email)
                    .data("fullName", fullName)
                    .data("plan", plan);
        } catch (Exception e) {
            LOG.error("Unexpected error during registration: {}", e.getMessage(), e);
            String msg = "An unexpected error occurred: " + summarizeException(e);
            if (wantsJson) {
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(java.util.Map.of("error", msg))
                        .build();
            }
            return signup.data("error", msg)
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
    public Object onboardingStep1(
            @QueryParam("plan") String plan, @QueryParam("error") String error) {
        UserContext uc = resolveUserContext();
        if (uc == null) return Response.seeOther(URI.create("/login")).build();

        String tenantId = TenantIdDeriver.derive(uc.username());
        var state = onboardingStateHolder.start(tenantId);
        if (plan != null && !plan.isBlank()) {
            state.setPlan(plan);
        }
        return onboardingWorkspace
                .data("error", error != null && !error.isBlank() ? error : null)
                .data("workspaceName", "");
    }

    @POST
    @Path("onboarding")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Object processOnboardingStep1(@FormParam("workspaceName") String workspaceName) {

        UserContext uc = resolveUserContext();
        if (uc == null) return Response.seeOther(URI.create("/login")).build();

        String tenantId = TenantIdDeriver.derive(uc.username());
        var state = onboardingStateHolder.get(tenantId);
        if (state == null) state = onboardingStateHolder.start(tenantId);

        String trimmedName = workspaceName != null ? workspaceName.trim() : "My Organization";
        if (trimmedName.isEmpty()) trimmedName = "My Organization";
        state.setWorkspaceName(trimmedName);

        // One-org-per-account guard: block if the user already owns an org
        List<OrganizationInfo> existingOrgs = resolveUserOrganizations(uc);
        if (!existingOrgs.isEmpty()) {
            return onboardingWorkspace
                    .data(
                            "error",
                            "You already belong to an organization. Only one organization per"
                                    + " account is allowed.")
                    .data("workspaceName", trimmedName);
        }

        // Always create an organization — personal workspaces are not supported.
        if (!cqrs.isCqrsAvailable()) {
            return onboardingWorkspace
                    .data("error", "Organization creation is temporarily unavailable.")
                    .data("workspaceName", trimmedName);
        }
        try {
            String orgName =
                    TenantIdDeriver.derive(trimmedName.toLowerCase().replaceAll("[^a-z0-9]", ""));
            String kcUserId = resolveKeycloakUserId(uc);

            var orchestrator =
                    new RegisterOrganizationOrchestrator(
                            cqrs.iamDispatcher(),
                            cqrs.coreDispatcher(),
                            cqrs.billingDispatcher(),
                            cqrs.isPermissionServiceAvailable() ? cqrs.permissionService() : null,
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
            String userMessage = extractUserFriendlyOrgError(e);
            return onboardingWorkspace
                    .data("error", userMessage)
                    .data("workspaceName", trimmedName);
        } catch (Exception e) {
            LOG.error("Unexpected error creating org in onboarding: {}", e.getMessage(), e);
            return onboardingWorkspace
                    .data("error", "An unexpected error occurred: " + summarizeException(e))
                    .data("workspaceName", trimmedName);
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

        // Seed governance and billing under the org tenant when one was created
        // during step 1, otherwise under the personal tenant.
        String targetTenantId = state.activeTenantId();
        provisioningTracker.start(tenantId, DeferredProvisioningTask.totalSteps());
        provisioningExecutor.submit(
                new DeferredProvisioningTask(
                        targetTenantId,
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

        // Set the active-org cookie so the dashboard opens in the org workspace
        String orgTenantId = state.orgTenantId();
        onboardingStateHolder.complete(tenantId);

        return Response.seeOther(URI.create("/onboarding/complete"))
                .cookie(AuthCookieHelper.setActiveOrgCookie(orgTenantId))
                .build();
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
     *
     * @param personalTenantId the user's personal tenant ID
     * @param effectiveTenantId the currently active tenant (org or personal)
     */
    private Response checkOnboardingGuard(
            String personalTenantId,
            String effectiveTenantId,
            List<OrganizationInfo> organizations) {
        if (personalTenantId == null) return null;
        ProvisioningTracker.ProvisioningStatus provStatus =
                provisioningTracker.getStatus(personalTenantId);
        if (provStatus != null && provStatus.status() == ProvisioningTracker.Status.IN_PROGRESS) {
            return Response.seeOther(URI.create("/onboarding/complete")).build();
        }
        if (provStatus != null && provStatus.status() == ProvisioningTracker.Status.FAILED) {
            String msg =
                    provStatus.errorMessage() != null
                            ? provStatus.errorMessage()
                            : "Workspace setup did not complete.";
            return Response.seeOther(URI.create("/onboarding?error=" + urlEncode(msg))).build();
        }
        if (hasCompletedOnboarding(personalTenantId)) return null;
        // When seed data was placed under an org tenant, check that tenant too
        if (effectiveTenantId != null
                && !effectiveTenantId.equals(personalTenantId)
                && hasCompletedOnboarding(effectiveTenantId)) {
            return null;
        }
        // After logout/login the active-org cookie is cleared, so effectiveTenantId
        // falls back to personalTenantId. Check all orgs the user belongs to — if any
        // has a subscription, onboarding was already completed.
        if (organizations != null) {
            for (OrganizationInfo org : organizations) {
                if (org.orgName() != null
                        && !org.orgName().equals(personalTenantId)
                        && !org.orgName().equals(effectiveTenantId)
                        && hasCompletedOnboarding(org.orgName())) {
                    return null;
                }
            }
        }
        return Response.seeOther(
                        URI.create(
                                "/onboarding?error="
                                        + urlEncode(
                                                "Your previous setup did not complete. Please"
                                                        + " complete onboarding to continue.")))
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

        // Server-side form submission is a fallback when JS is disabled.
        // Since DPoP-based auth requires JavaScript, redirect to the login page
        // so the JS flow can handle authentication properly.
        return login.data("error", "Please enable JavaScript to sign in.")
                .data("identifier", identifier);
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

        WorkspaceContext ws = resolveWorkspaceContext(uc);

        // Guard: redirect if onboarding is in progress or never completed
        Response onboardingRedirect =
                checkOnboardingGuard(ws.personalTenantId(), ws.tenantId(), ws.organizations());
        if (onboardingRedirect != null) return onboardingRedirect;

        return applyDashboardData(dashboard.instance(), uc, ws, "overview");
    }

    @GET
    @Path("dashboard/account")
    @Produces(MediaType.TEXT_HTML)
    public Object accountPage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        WorkspaceContext ws = resolveWorkspaceContext(uc);

        Response onboardingRedirect =
                checkOnboardingGuard(ws.personalTenantId(), ws.tenantId(), ws.organizations());
        if (onboardingRedirect != null) return onboardingRedirect;

        String fullName =
                uc.jwtPayload() != null ? extractJsonString(uc.jwtPayload(), "name") : null;

        return applyDashboardData(account.instance(), uc, ws, "account")
                .data("fullName", fullName)
                .data("v", BUILD_VERSION);
    }

    @GET
    @Path("dashboard/billing")
    @Produces(MediaType.TEXT_HTML)
    public Object billingPage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        WorkspaceContext ws = resolveWorkspaceContext(uc);

        Response onboardingRedirect =
                checkOnboardingGuard(ws.personalTenantId(), ws.tenantId(), ws.organizations());
        if (onboardingRedirect != null) return onboardingRedirect;

        return applyDashboardData(billing.instance(), uc, ws, "billing").data("v", BUILD_VERSION);
    }

    @GET
    @Path("dashboard/reports")
    @Produces(MediaType.TEXT_HTML)
    public Object reportsPage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        WorkspaceContext ws = resolveWorkspaceContext(uc);

        Response onboardingRedirect =
                checkOnboardingGuard(ws.personalTenantId(), ws.tenantId(), ws.organizations());
        if (onboardingRedirect != null) return onboardingRedirect;

        return applyDashboardData(reports.instance(), uc, ws, "reports").data("v", BUILD_VERSION);
    }

    @GET
    @Path("dashboard/analytics")
    @Produces(MediaType.TEXT_HTML)
    public Object analyticsPage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        WorkspaceContext ws = resolveWorkspaceContext(uc);

        Response onboardingRedirect =
                checkOnboardingGuard(ws.personalTenantId(), ws.tenantId(), ws.organizations());
        if (onboardingRedirect != null) return onboardingRedirect;

        return applyDashboardData(analytics.instance(), uc, ws, "analytics")
                .data("v", BUILD_VERSION);
    }

    @GET
    @Path("dashboard/dsar")
    @Produces(MediaType.TEXT_HTML)
    public Object dsarPage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        WorkspaceContext ws = resolveWorkspaceContext(uc);

        Response onboardingRedirect =
                checkOnboardingGuard(ws.personalTenantId(), ws.tenantId(), ws.organizations());
        if (onboardingRedirect != null) return onboardingRedirect;

        return applyDashboardData(dsar.instance(), uc, ws, "dsar").data("v", BUILD_VERSION);
    }

    @GET
    @Path("dashboard/webhooks")
    @Produces(MediaType.TEXT_HTML)
    public Object webhooksPage() {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        WorkspaceContext ws = resolveWorkspaceContext(uc);

        Response onboardingRedirect =
                checkOnboardingGuard(ws.personalTenantId(), ws.tenantId(), ws.organizations());
        if (onboardingRedirect != null) return onboardingRedirect;

        return applyDashboardData(webhooks.instance(), uc, ws, "webhooks")
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

        WorkspaceContext ws = resolveWorkspaceContext(uc);

        Response onboardingRedirect =
                checkOnboardingGuard(ws.personalTenantId(), ws.tenantId(), ws.organizations());
        if (onboardingRedirect != null) return onboardingRedirect;

        return applyDashboardData(governance.instance(), uc, ws, "governance")
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

        WorkspaceContext ws = resolveWorkspaceContext(uc);

        Response onboardingRedirect =
                checkOnboardingGuard(ws.personalTenantId(), ws.tenantId(), ws.organizations());
        if (onboardingRedirect != null) return onboardingRedirect;

        return applyDashboardData(catalogue.instance(), uc, ws, "catalogue")
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

        WorkspaceContext ws = resolveWorkspaceContext(uc);

        Response onboardingRedirect =
                checkOnboardingGuard(ws.personalTenantId(), ws.tenantId(), ws.organizations());
        if (onboardingRedirect != null) return onboardingRedirect;

        return applyDashboardData(consent.instance(), uc, ws, "consent").data("v", BUILD_VERSION);
    }

    @GET
    @Path("dashboard/organizations/{orgId}")
    @Produces(MediaType.TEXT_HTML)
    public Object orgDetailPage(@PathParam("orgId") String orgId) {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        WorkspaceContext ws = resolveWorkspaceContext(uc);

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

        return applyDashboardData(orgDetail.instance(), uc, ws, "account")
                .data("orgId", orgId)
                .data("orgName", orgName)
                .data("orgDisplayName", orgDisplayName);
    }

    // -----------------------------------------------------------------------
    // Workspace switching
    // -----------------------------------------------------------------------

    /**
     * Switches the active organization by setting the {@code cveil_active_org} cookie. Only
     * switches to organizations the user is a member of.
     */
    @POST
    @Path("dashboard/switch-org")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response switchOrganization(@FormParam("orgName") String orgName) {
        UserContext uc = resolveUserContext();
        if (uc == null) {
            return Response.seeOther(URI.create("/login")).build();
        }

        if (orgName == null || orgName.isBlank()) {
            return Response.seeOther(URI.create("/dashboard")).build();
        }

        // Validate that the user is a member of the requested org
        List<OrganizationInfo> orgs = resolveUserOrganizations(uc);
        boolean isMember = orgs.stream().anyMatch(o -> orgName.equals(o.orgName()));

        if (!isMember) {
            LOG.warn(
                    "User '{}' attempted to switch to org '{}' but is not a member",
                    uc.username(),
                    orgName);
            return Response.seeOther(URI.create("/dashboard")).build();
        }

        return Response.seeOther(URI.create("/dashboard"))
                .cookie(AuthCookieHelper.setActiveOrgCookie(orgName))
                .build();
    }

    // -----------------------------------------------------------------------
    // Logout
    // -----------------------------------------------------------------------

    @POST
    @Path("logout")
    @Produces(MediaType.TEXT_HTML)
    public Response processLogout() {
        // Token revocation is handled client-side via POST /api/v1/platform/iam/logout
        // before the browser navigates here. This server-side handler just clears cookies.
        NewCookie clearSession =
                new NewCookie.Builder(AuthCookieHelper.SESSION_COOKIE)
                        .value("")
                        .path("/")
                        .maxAge(0)
                        .sameSite(NewCookie.SameSite.LAX)
                        .build();
        NewCookie clearOrg = AuthCookieHelper.clearActiveOrgCookie();
        // Also expire legacy HttpOnly cookies in case they linger from an old session
        NewCookie[] clearedAuth = AuthCookieHelper.clearAuthCookies();
        return Response.seeOther(URI.create("/login"))
                .cookie(clearedAuth[0], clearedAuth[1], clearSession, clearOrg)
                .build();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Resolved workspace context for dashboard pages — encapsulates the effective tenant ID,
     * display label, and the user's available workspaces (personal + organizations).
     *
     * @param tenantId the effective tenant ID (org's orgName or personal tenant)
     * @param displayName human-readable label ("Personal" or org display name)
     * @param activeOrgId the Keycloak org ID of the active org, or {@code null} for personal
     * @param personalTenantId the user's personal tenant ID (always available)
     * @param organizations organizations the user belongs to (may be empty)
     */
    record WorkspaceContext(
            String tenantId,
            String displayName,
            String activeOrgId,
            String personalTenantId,
            List<OrganizationInfo> organizations) {}

    /**
     * Resolves the effective workspace context for the current request. Checks the {@code
     * cveil_active_org} cookie; if set to a valid org the user belongs to, uses that org's {@code
     * orgName} as tenantId. Otherwise falls back to the user's personal tenant.
     */
    private WorkspaceContext resolveWorkspaceContext(UserContext uc) {
        String username = uc.username();
        String personalTenantId = username != null ? TenantIdDeriver.derive(username) : null;

        // Try to load user's org memberships
        List<OrganizationInfo> organizations = resolveUserOrganizations(uc);

        // Check the active org cookie
        String cookieHeader = httpHeaders.getHeaderString("Cookie");
        String activeOrgName = AuthCookieHelper.readActiveOrg(cookieHeader);

        if (activeOrgName != null && !activeOrgName.isBlank()) {
            // Validate the user is actually a member of this org
            for (OrganizationInfo org : organizations) {
                if (activeOrgName.equals(org.orgName())) {
                    String display = org.displayName() != null ? org.displayName() : org.orgName();
                    return new WorkspaceContext(
                            org.orgName(), display, org.orgId(), personalTenantId, organizations);
                }
            }
            LOG.debug(
                    "Active org cookie '{}' does not match any user org membership", activeOrgName);
        }

        // No valid org cookie — auto-select the first org (org-only model)
        if (!organizations.isEmpty()) {
            OrganizationInfo first = organizations.get(0);
            String display = first.displayName() != null ? first.displayName() : first.orgName();
            return new WorkspaceContext(
                    first.orgName(), display, first.orgId(), personalTenantId, organizations);
        }

        // Fallback for users who haven't completed onboarding yet
        return new WorkspaceContext(
                personalTenantId, "Personal", null, personalTenantId, organizations);
    }

    /**
     * Resolves the list of organizations the current user belongs to by querying the {@code
     * GetCurrentUserQueryActuator} via the IAM query dispatcher.
     */
    private List<OrganizationInfo> resolveUserOrganizations(UserContext uc) {
        if (!cqrs.isCqrsAvailable()) return List.of();

        String userId = resolveKeycloakUserId(uc);
        if (userId == null) return List.of();

        try {
            var result =
                    cqrs.iamQueryDispatcher()
                            .dispatch(new GetCurrentUserQueryActuator.GetCurrentUserQuery(userId));
            if (result instanceof GetCurrentUserQueryActuator.CurrentUserResult cur) {
                return cur.organizations() != null ? cur.organizations() : List.of();
            }
        } catch (Exception e) {
            LOG.debug("Failed to resolve user organizations: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Populates common template data shared by all dashboard pages: workspace context (tenantId,
     * display name, org list), user info, roles, and navigation state.
     */
    private TemplateInstance applyDashboardData(
            TemplateInstance template, UserContext uc, WorkspaceContext ws, String activeNav) {
        String username = uc.username();
        String initial =
                username != null && !username.isEmpty()
                        ? username.substring(0, 1).toUpperCase()
                        : "U";
        return template.data("username", username)
                .data("email", uc.email())
                .data("tenantId", ws.tenantId())
                .data("workspaceDisplayName", ws.displayName())
                .data("activeOrgId", ws.activeOrgId())
                .data("personalTenantId", ws.personalTenantId())
                .data("organizations", ws.organizations())
                .data(
                        "roles",
                        uc.jwtPayload() != null ? resolveRolesDisplay(uc.jwtPayload()) : "member")
                .data("initial", initial)
                .data("activeNav", activeNav);
    }

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
     * Resolves user context for server-rendered dashboard pages from the lightweight {@code
     * cveil_session} cookie set by the DPoP login flow.
     *
     * @return resolved context, or {@code null} if the user is not authenticated
     */
    private UserContext resolveUserContext() {
        String cookieHeader = httpHeaders.getHeaderString("Cookie");

        String sessionJson = AuthCookieHelper.decodeSessionCookie(cookieHeader);
        if (sessionJson != null) {
            String u = extractJsonString(sessionJson, "u");
            String e = extractJsonString(sessionJson, "e");
            String s = extractJsonString(sessionJson, "s");
            if (u != null) {
                // Build a synthetic JWT-like payload with the sub claim so that
                // resolveKeycloakUserId() returns the Keycloak UUID (not the username).
                String syntheticPayload =
                        s != null
                                ? "{\"sub\":\"" + s + "\",\"preferred_username\":\"" + u + "\"}"
                                : null;
                return new UserContext(u, e, syntheticPayload);
            }
        }

        return null;
    }

    // -----------------------------------------------------------------------
    // Error message helpers
    // -----------------------------------------------------------------------

    /** Extracts a user-friendly error message from an org-creation OrchestratorException. */
    private static String extractUserFriendlyOrgError(OrchestratorException e) {
        String rootMsg = rootCauseMessage(e);
        if (rootMsg.contains("conflicts")) {
            return "An organization with that name already exists. Please choose a different"
                    + " workspace name.";
        }
        if (rootMsg.contains("401") || rootMsg.contains("Unauthorized")) {
            return "Identity provider authentication failed. Please contact support.";
        }
        if (rootMsg.contains("403") || rootMsg.contains("Forbidden")) {
            return "You do not have permission to create an organization. Please contact support.";
        }
        return "Could not create organization: "
                + summarizeException(e)
                + ". Please try a different name or uncheck the team option.";
    }

    /** Extracts a user-friendly error message from a signup OrchestratorException. */
    private static String extractUserFriendlySignupError(OrchestratorException e) {
        String rootMsg = rootCauseMessage(e);
        if (rootMsg.contains("409")
                || rootMsg.contains("Conflict")
                || rootMsg.contains("already exists")
                || rootMsg.contains("User exists")) {
            return "An account with that email or username already exists. Please log in or use a"
                    + " different email.";
        }
        if (rootMsg.contains("401") || rootMsg.contains("Unauthorized")) {
            return "Identity provider authentication failed. Please try again or contact support.";
        }
        if (rootMsg.contains("404") || rootMsg.contains("Not Found")) {
            return "The identity service is not fully configured. Please contact support.";
        }
        return "Registration failed at step '"
                + e.failedStep()
                + "': "
                + summarizeException(e)
                + ". Please try again or contact support.";
    }

    /** Walks the exception cause chain and returns the deepest cause's message. */
    private static String rootCauseMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    /** Returns a short summary of an exception suitable for display to users. */
    private static String summarizeException(Throwable t) {
        String msg = rootCauseMessage(t);
        // Truncate long stack-trace-style messages for user display
        if (msg.length() > 150) {
            msg = msg.substring(0, 147) + "...";
        }
        return msg;
    }

    /** URL-encodes a string for safe use in query parameters. */
    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
