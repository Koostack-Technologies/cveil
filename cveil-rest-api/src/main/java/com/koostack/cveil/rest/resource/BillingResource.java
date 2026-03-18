package com.koostack.cveil.rest.resource;

import com.koostack.cveil.core.platform.billing.models.InvoiceInfo;
import com.koostack.cveil.core.platform.billing.models.PaymentMethodDetail;
import com.koostack.cveil.core.platform.billing.models.PaymentMethodInfo;
import com.koostack.cveil.core.platform.billing.models.SetupIntentDetail;
import com.koostack.cveil.core.platform.billing.models.SubscriptionDetail;
import com.koostack.cveil.core.platform.billing.models.SubscriptionInfo;
import com.koostack.cveil.core.platform.plan.PlanQueryService;
import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.config.PlatformCqrsHolder;
import com.koostack.cveil.rest.context.CveilRequestHolder;
import com.koostack.cveil.rest.dto.BillingDtos.ChangeSubscriptionRequest;
import com.koostack.cveil.rest.dto.BillingDtos.CreateSubscriptionRequest;
import com.koostack.cveil.rest.dto.BillingDtos.InvoiceResponse;
import com.koostack.cveil.rest.dto.BillingDtos.PaymentMethodResponse;
import com.koostack.cveil.rest.dto.BillingDtos.PlanResponse;
import com.koostack.cveil.rest.dto.BillingDtos.SetupIntentResponse;
import com.koostack.cveil.rest.dto.BillingDtos.StripeConfigResponse;
import com.koostack.cveil.rest.dto.BillingDtos.SubscriptionResponse;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/v1/billing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(ref = CveilOpenApiConfig.TAG_BILLING)
@SecurityRequirement(name = CveilOpenApiConfig.SECURITY_SCHEME)
public class BillingResource {

    private static final Logger LOG = LoggerFactory.getLogger(BillingResource.class);

    @Inject PlatformCqrsHolder cqrs;
    @Inject CveilRequestHolder requestHolder;

    // -- Stripe config (public key for frontend) --------------------------------

    @GET
    @Path("/stripe-config")
    @Operation(operationId = "getStripeConfig", summary = "Get Stripe publishable key for frontend")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Stripe config returned"),
        @APIResponse(responseCode = "503", description = "Payment processor not configured")
    })
    public Response getStripeConfig(@Context SecurityContext sc) {
        requireAuthenticated();
        if (cqrs.paymentProcessorService() == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(
                            ErrorResponse.of(
                                    "service_unavailable", "Payment processor not configured"))
                    .build();
        }
        String publishableKey = cqrs.paymentProcessorService().getPublishableKey();
        return Response.ok(new StripeConfigResponse(publishableKey)).build();
    }

    // -- Setup Intent -----------------------------------------------------------

    @POST
    @Path("/setup-intent")
    @Operation(
            operationId = "createSetupIntent",
            summary = "Create a Stripe SetupIntent for card collection")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "SetupIntent created"),
        @APIResponse(responseCode = "503", description = "Payment processor not configured")
    })
    public Response createSetupIntent(@Context SecurityContext sc) {
        String tenantId = requireTenantId();
        requirePaymentProcessor();

        var profile = cqrs.billingQueryService().getBillingProfile(tenantId);
        if (profile == null || profile.paymentProcessorCustomerId() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.of(
                                    "validation_error",
                                    "Billing profile not found or no Stripe customer"))
                    .build();
        }

        SetupIntentDetail intent =
                cqrs.paymentProcessorService()
                        .createSetupIntent(profile.paymentProcessorCustomerId());
        return Response.ok(new SetupIntentResponse(intent.id(), intent.clientSecret())).build();
    }

    // -- Payment Methods --------------------------------------------------------

    @GET
    @Path("/payment-methods")
    @Operation(operationId = "listPaymentMethods", summary = "List active payment methods")
    @APIResponse(responseCode = "200", description = "Payment methods returned")
    public Response listPaymentMethods(@Context SecurityContext sc) {
        String tenantId = requireTenantId();

        List<PaymentMethodInfo> methods = cqrs.billingQueryService().listPaymentMethods(tenantId);
        List<PaymentMethodResponse> response =
                methods.stream().map(PaymentMethodResponse::fromModel).toList();
        return Response.ok(response).build();
    }

    @POST
    @Path("/payment-methods/{paymentMethodId}/attach")
    @Operation(
            operationId = "attachPaymentMethod",
            summary = "Attach a payment method from Stripe to this tenant")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Payment method attached"),
        @APIResponse(
                responseCode = "400",
                description = "Invalid payment method or billing profile")
    })
    public Response attachPaymentMethod(
            @Context SecurityContext sc,
            @Parameter(description = "Stripe payment method ID (pm_xxx)")
                    @PathParam("paymentMethodId")
                    String paymentMethodId,
            @QueryParam("setDefault") @DefaultValue("false") boolean setDefault) {
        String tenantId = requireTenantId();
        requirePaymentProcessor();

        var profile = cqrs.billingQueryService().getBillingProfile(tenantId);
        if (profile == null || profile.paymentProcessorCustomerId() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.of(
                                    "validation_error", "No billing profile or Stripe customer"))
                    .build();
        }

        // Attach in Stripe
        PaymentMethodDetail detail =
                cqrs.paymentProcessorService()
                        .attachPaymentMethod(profile.paymentProcessorCustomerId(), paymentMethodId);

        if (setDefault) {
            cqrs.paymentProcessorService()
                    .setDefaultPaymentMethod(profile.paymentProcessorCustomerId(), paymentMethodId);
        }

        // Record locally via command — compensate in Stripe if local persistence fails
        try {
            cqrs.billingCommandService()
                    .attachPaymentMethod(
                            tenantId,
                            detail.externalId(),
                            detail.type(),
                            detail.brand(),
                            detail.last4(),
                            detail.expMonth(),
                            detail.expYear(),
                            setDefault);
        } catch (Exception e) {
            LOG.error(
                    "Local command failed after Stripe attach for pm {} — "
                            + "compensating by detaching from Stripe",
                    paymentMethodId,
                    e);
            try {
                cqrs.paymentProcessorService().detachPaymentMethod(paymentMethodId);
            } catch (Exception compensateEx) {
                LOG.error(
                        "Compensating Stripe detach also failed for pm {} — "
                                + "manual intervention required",
                        paymentMethodId,
                        compensateEx);
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(
                            ErrorResponse.of(
                                    "internal_error", "Failed to record payment method locally"))
                    .build();
        }

        LOG.info("Payment method {} attached for tenant {}", paymentMethodId, tenantId);
        return Response.ok(Map.of("status", "attached", "paymentMethodId", paymentMethodId))
                .build();
    }

    @DELETE
    @Path("/payment-methods/{paymentMethodId}")
    @Operation(operationId = "detachPaymentMethod", summary = "Detach a payment method")
    @APIResponse(responseCode = "200", description = "Payment method detached")
    public Response detachPaymentMethod(
            @Context SecurityContext sc, @PathParam("paymentMethodId") String paymentMethodId) {
        String tenantId = requireTenantId();
        requirePaymentProcessor();

        cqrs.paymentProcessorService().detachPaymentMethod(paymentMethodId);
        cqrs.billingCommandService().detachPaymentMethod(tenantId, paymentMethodId);

        LOG.info("Payment method {} detached for tenant {}", paymentMethodId, tenantId);
        return Response.ok(Map.of("status", "detached")).build();
    }

    @PUT
    @Path("/payment-methods/{paymentMethodId}/default")
    @Operation(operationId = "setDefaultPaymentMethod", summary = "Set a payment method as default")
    @APIResponse(responseCode = "200", description = "Default payment method updated")
    public Response setDefaultPaymentMethod(
            @Context SecurityContext sc, @PathParam("paymentMethodId") String paymentMethodId) {
        String tenantId = requireTenantId();
        requirePaymentProcessor();

        var profile = cqrs.billingQueryService().getBillingProfile(tenantId);
        if (profile == null || profile.paymentProcessorCustomerId() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("validation_error", "No billing profile"))
                    .build();
        }

        cqrs.paymentProcessorService()
                .setDefaultPaymentMethod(profile.paymentProcessorCustomerId(), paymentMethodId);
        cqrs.billingCommandService().setDefaultPaymentMethod(tenantId, paymentMethodId);

        LOG.info("Default payment method set to {} for tenant {}", paymentMethodId, tenantId);
        return Response.ok(Map.of("status", "default_set")).build();
    }

    // -- Invoices ---------------------------------------------------------------

    @GET
    @Path("/invoices")
    @Operation(operationId = "listInvoices", summary = "List invoices for the current tenant")
    @APIResponse(responseCode = "200", description = "Invoices returned")
    public Response listInvoices(
            @Context SecurityContext sc,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        String tenantId = requireTenantId();

        if (pageSize > 100) pageSize = 100;
        List<InvoiceInfo> invoices =
                cqrs.billingQueryService().listInvoices(tenantId, page, pageSize);
        List<InvoiceResponse> response = invoices.stream().map(InvoiceResponse::fromModel).toList();
        return Response.ok(response).build();
    }

    @GET
    @Path("/invoices/{invoiceId}")
    @Operation(operationId = "getInvoice", summary = "Get invoice details with line items")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invoice details returned"),
        @APIResponse(responseCode = "404", description = "Invoice not found")
    })
    public Response getInvoice(
            @Context SecurityContext sc, @PathParam("invoiceId") String invoiceId) {
        String tenantId = requireTenantId();

        InvoiceInfo invoice = cqrs.billingQueryService().getInvoice(tenantId, invoiceId);
        if (invoice == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ErrorResponse.of("not_found", "Invoice not found"))
                    .build();
        }
        return Response.ok(InvoiceResponse.fromModel(invoice)).build();
    }

    // -- Subscription Management ------------------------------------------------

    // Pricing and feature metadata — limits come from plan table via PlanQueryService
    private record PlanPricing(int priceInCents, boolean contactSales, List<String> features) {}

    private static final Map<String, PlanPricing> PLAN_PRICING =
            Map.of(
                    "free",
                            new PlanPricing(
                                    0,
                                    false,
                                    List.of(
                                            "Core consent graph operations",
                                            "Basic field masking",
                                            "Standard purpose & category definitions",
                                            "Single tenant environment",
                                            "Community support")),
                    "pro",
                            new PlanPricing(
                                    4900,
                                    false,
                                    List.of(
                                            "Everything in Starter",
                                            "Governance engine with regulation mapping",
                                            "DSAR exports & erasure receipts",
                                            "30-day audit log retention",
                                            "Basic RBAC for org members",
                                            "Email support (48hr response)")),
                    "business",
                            new PlanPricing(
                                    19900,
                                    false,
                                    List.of(
                                            "Everything in Growth",
                                            "Advanced multi-region governance",
                                            "High-performance evaluations & SSE invalidations",
                                            "Full DSAR lifecycle management",
                                            "Extended audit retention (1–7 years)",
                                            "Advanced identity integration",
                                            "Priority support with SLA")),
                    "enterprise",
                            new PlanPricing(
                                    0,
                                    true,
                                    List.of(
                                            "Everything in Business",
                                            "Dedicated single-tenant infrastructure",
                                            "Custom masking & cryptographic operations",
                                            "Full platform API access",
                                            "Custom billing & tenant management APIs",
                                            "24/7 support with engineering escalation")));

    @GET
    @Path("/plans")
    @Operation(operationId = "listPlans", summary = "List available billing plans")
    @APIResponse(responseCode = "200", description = "Plans returned")
    public Response listPlans(@Context SecurityContext sc) {
        requireAuthenticated();
        var plans = cqrs.planQueryService().listPlans();
        List<PlanResponse> response =
                plans.stream()
                        .filter(p -> p.isPublic() || p.isActive())
                        .sorted((a, b) -> Integer.compare(a.sortOrder(), b.sortOrder()))
                        .map(
                                p -> {
                                    var pricing =
                                            PLAN_PRICING.getOrDefault(
                                                    p.planId(),
                                                    new PlanPricing(0, false, List.of()));
                                    return new PlanResponse(
                                            p.planId(),
                                            p.displayName(),
                                            p.description(),
                                            pricing.priceInCents(),
                                            (int) p.evalLimit(),
                                            (int) p.subjectLimit(),
                                            p.burstLimit(),
                                            pricing.contactSales(),
                                            pricing.features());
                                })
                        .toList();
        return Response.ok(response).build();
    }

    @GET
    @Path("/subscription")
    @Operation(operationId = "getSubscription", summary = "Get current subscription")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Subscription returned"),
        @APIResponse(responseCode = "401", description = "Not authenticated")
    })
    public Response getSubscription(@Context SecurityContext sc) {
        String tenantId = requireTenantId();
        SubscriptionInfo sub = cqrs.billingQueryService().getSubscription(tenantId);
        if (sub == null) {
            return Response.ok(Map.of("planTier", "free", "status", "none")).build();
        }
        return Response.ok(SubscriptionResponse.fromModel(sub)).build();
    }

    @POST
    @Path("/subscription")
    @Operation(operationId = "createSubscription", summary = "Subscribe to a plan")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Subscription created"),
        @APIResponse(responseCode = "400", description = "Invalid plan or billing profile"),
        @APIResponse(responseCode = "409", description = "Already subscribed")
    })
    public Response createSubscription(
            @Context SecurityContext sc, CreateSubscriptionRequest request) {
        String tenantId = requireTenantId();
        requirePaymentProcessor();

        if (request == null || request.planTier() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("validation_error", "planTier is required"))
                    .build();
        }

        PlanQueryService.PlanInfo plan = cqrs.planQueryService().getPlan(request.planTier());
        if (plan == null || "free".equals(plan.planId())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("validation_error", "Invalid plan tier"))
                    .build();
        }
        if (!plan.isActive()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("validation_error", "Plan is not active"))
                    .build();
        }

        // Check for existing subscription
        SubscriptionInfo existing = cqrs.billingQueryService().getSubscription(tenantId);
        if (existing != null && "active".equals(existing.status())) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(
                            ErrorResponse.of(
                                    "conflict",
                                    "Active subscription exists — use PUT to change plan"))
                    .build();
        }

        var profile = cqrs.billingQueryService().getBillingProfile(tenantId);
        if (profile == null || profile.paymentProcessorCustomerId() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.of(
                                    "validation_error",
                                    "No billing profile — add a payment method first"))
                    .build();
        }

        // Create subscription via payment processor SPI
        String interval = request.effectiveInterval();
        String priceId;
        if ("annual".equals(interval)
                && plan.stripeAnnualPriceId() != null
                && !plan.stripeAnnualPriceId().isBlank()) {
            priceId = plan.stripeAnnualPriceId();
        } else {
            priceId = plan.stripePriceId();
            if ("annual".equals(interval)) {
                LOG.warn(
                        "Annual price not available for plan {} — falling back to monthly",
                        plan.planId());
            }
        }
        if (priceId == null || priceId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.of(
                                    "configuration_error",
                                    "Stripe price ID not configured for plan: " + plan.planId()))
                    .build();
        }

        SubscriptionDetail detail =
                cqrs.paymentProcessorService()
                        .createSubscription(profile.paymentProcessorCustomerId(), priceId);

        // Record locally
        cqrs.billingCommandService()
                .createSubscription(
                        tenantId,
                        plan.planId(),
                        (int) plan.evalLimit(),
                        (int) plan.subjectLimit(),
                        plan.burstLimit(),
                        detail.externalSubscriptionId(),
                        detail.currentPeriodStart(),
                        detail.currentPeriodEnd());

        LOG.info(
                "Subscription created for tenant {} on plan {} (external: {})",
                tenantId,
                plan.planId(),
                detail.externalSubscriptionId());
        return Response.ok(
                        Map.of(
                                "status",
                                "created",
                                "planTier",
                                plan.planId(),
                                "externalSubscriptionId",
                                detail.externalSubscriptionId()))
                .build();
    }

    @PUT
    @Path("/subscription")
    @Operation(operationId = "changeSubscription", summary = "Change subscription plan")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Subscription changed"),
        @APIResponse(responseCode = "400", description = "Invalid plan or no active subscription")
    })
    public Response changeSubscription(
            @Context SecurityContext sc, ChangeSubscriptionRequest request) {
        String tenantId = requireTenantId();
        requirePaymentProcessor();

        if (request == null || request.planTier() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("validation_error", "planTier is required"))
                    .build();
        }

        PlanQueryService.PlanInfo plan = cqrs.planQueryService().getPlan(request.planTier());
        if (plan == null || "free".equals(plan.planId())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.of(
                                    "validation_error",
                                    "Invalid plan tier — to downgrade to free, cancel instead"))
                    .build();
        }
        if (!plan.isActive()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("validation_error", "Plan is not active"))
                    .build();
        }

        SubscriptionInfo existing = cqrs.billingQueryService().getSubscription(tenantId);
        if (existing == null || !"active".equals(existing.status())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.of(
                                    "validation_error", "No active subscription to change"))
                    .build();
        }

        String externalSubId = existing.externalSubscriptionId();
        if (externalSubId == null || externalSubId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ErrorResponse.of("validation_error", "No external subscription found"))
                    .build();
        }

        String changeInterval = request.effectiveInterval();
        String priceId;
        if ("annual".equals(changeInterval)
                && plan.stripeAnnualPriceId() != null
                && !plan.stripeAnnualPriceId().isBlank()) {
            priceId = plan.stripeAnnualPriceId();
        } else {
            priceId = plan.stripePriceId();
        }
        if (priceId == null || priceId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.of(
                                    "configuration_error",
                                    "Stripe price ID not configured for plan: " + plan.planId()))
                    .build();
        }

        cqrs.paymentProcessorService().updateSubscription(externalSubId, priceId);

        // Record locally
        cqrs.billingCommandService()
                .changeSubscriptionPlan(
                        tenantId,
                        existing.planTier(),
                        plan.planId(),
                        (int) plan.evalLimit(),
                        (int) plan.subjectLimit(),
                        plan.burstLimit());

        LOG.info("Subscription changed for tenant {} to plan {}", tenantId, plan.planId());
        return Response.ok(Map.of("status", "changed", "planTier", plan.planId())).build();
    }

    @DELETE
    @Path("/subscription")
    @Operation(operationId = "cancelSubscription", summary = "Cancel subscription")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Subscription cancelled"),
        @APIResponse(responseCode = "400", description = "No active subscription")
    })
    public Response cancelSubscription(@Context SecurityContext sc) {
        String tenantId = requireTenantId();
        requirePaymentProcessor();

        SubscriptionInfo existing = cqrs.billingQueryService().getSubscription(tenantId);
        if (existing == null || !"active".equals(existing.status())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.of(
                                    "validation_error", "No active subscription to cancel"))
                    .build();
        }

        String externalSubId = existing.externalSubscriptionId();
        if (externalSubId != null && !externalSubId.isBlank()) {
            cqrs.paymentProcessorService().cancelSubscription(externalSubId, true);
        }

        cqrs.billingCommandService().cancelSubscription(tenantId);

        LOG.info("Subscription cancelled for tenant {} (at period end)", tenantId);
        return Response.ok(Map.of("status", "cancelled", "cancelAtPeriodEnd", true)).build();
    }

    // -- Helpers ----------------------------------------------------------------

    private String requireTenantId() {
        requireAuthenticated();
        String tenantId = requestHolder.identity().tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID not available in request context");
        }
        return tenantId;
    }

    private void requireAuthenticated() {
        if (!requestHolder.isAuthenticated()) {
            throw new com.koostack.cveil.core.exception.CveilAuthException(
                    "Authentication required");
        }
    }

    private void requirePaymentProcessor() {
        if (cqrs.paymentProcessorService() == null) {
            throw new com.koostack.cveil.core.exception.CveilServiceException(
                    "Payment processor not configured");
        }
    }
}
