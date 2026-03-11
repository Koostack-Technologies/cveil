package com.koostack.operio.rest.site;

import com.koostack.operio.core.cqrs.AbstractOrchestrator.OrchestratorException;
import com.koostack.operio.rest.config.PlatformCqrsHolder;
import com.koostack.operio.rest.orchestrator.RegisterUserOrchestrator;
import com.koostack.operio.rest.util.TenantIdDeriver;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the public-facing Operio website pages using Qute templates.
 *
 * <p>These endpoints are unauthenticated (listed in {@code ApiKeyAuthFilter.isPublicPath}) and
 * produce HTML responses.
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

    @Inject PlatformCqrsHolder cqrs;

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
                            cqrs.iamDispatcher(), cqrs.coreDispatcher(), cqrs.billingDispatcher());

            var command =
                    new RegisterUserOrchestrator.RegisterUserCommand(
                            REALM, username, email, fullName, Set.of(), false, password, tenantId);

            RegisterUserOrchestrator.RegisterUserResult result = orchestrator.execute(command);
            return Response.seeOther(
                            URI.create(
                                    "/signup/success?username="
                                            + URLEncoder.encode(
                                                    result.username(), StandardCharsets.UTF_8)
                                            + "&tenant="
                                            + URLEncoder.encode(
                                                    result.tenantId(), StandardCharsets.UTF_8)))
                    .build();
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
