package com.koostack.cveil.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.platform.billing.models.WebhookEvent;
import com.koostack.cveil.rest.config.CveilOpenApiConfig;
import com.koostack.cveil.rest.config.PlatformCqrsHolder;
import com.koostack.cveil.rest.dto.CommonDtos.ErrorResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/v1/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Tag(ref = CveilOpenApiConfig.TAG_BILLING)
public class WebhookResource {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookResource.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject PlatformCqrsHolder cqrs;

    @POST
    @Path("/stripe")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "handleStripeWebhook", summary = "Receive Stripe webhook events")
    public Response handleStripeWebhook(
            @HeaderParam("Stripe-Signature") String signature, String payload) {

        if (cqrs.paymentProcessorService() == null) {
            LOG.warn("Stripe webhook received but payment processor not configured");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(
                            ErrorResponse.of(
                                    "service_unavailable", "Payment processor not configured"))
                    .build();
        }

        // 1. Verify signature
        WebhookEvent event;
        try {
            event = cqrs.paymentProcessorService().verifyAndParseWebhook(payload, signature);
        } catch (Exception e) {
            LOG.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            ErrorResponse.of(
                                    "invalid_signature", "Webhook signature verification failed"))
                    .build();
        }

        LOG.info(
                "Stripe webhook received: type={}, id={}",
                event.eventType(),
                event.externalEventId());

        // 2. Idempotency check
        if (cqrs.billingQueryService().isWebhookEventProcessed(event.externalEventId())) {
            LOG.info("Stripe webhook already processed: id={}", event.externalEventId());
            return Response.ok(Map.of("received", true, "duplicate", true)).build();
        }

        // 3. Route by event type
        try {
            JsonNode root = mapper.readTree(event.payload());
            JsonNode dataObject = root.path("data").path("object");

            switch (event.eventType()) {
                case "invoice.created",
                        "invoice.updated",
                        "invoice.finalized" -> handleInvoiceEvent(dataObject, event.eventType());
                case "invoice.paid", "invoice.payment_succeeded" -> handleInvoicePaid(dataObject);
                case "payment_method.attached" -> handlePaymentMethodAttached(dataObject);
                case "payment_method.detached" -> handlePaymentMethodDetached(dataObject);
                case "customer.subscription.created", "customer.subscription.updated" -> LOG.info(
                        "Subscription event: {} — handled via local commands", event.eventType());
                case "customer.subscription.deleted" -> LOG.info(
                        "Subscription deleted event — handled via local commands");
                default -> LOG.debug("Unhandled Stripe event type: {}", event.eventType());
            }
        } catch (Exception e) {
            LOG.error(
                    "Failed to process Stripe webhook: type={}, id={}",
                    event.eventType(),
                    event.externalEventId(),
                    e);
            // Return 200 to prevent Stripe retries for processing errors
        }

        // 4. Mark as processed
        try {
            cqrs.billingQueryService()
                    .markWebhookEventProcessed(
                            event.externalEventId(), event.eventType(), event.payload());
        } catch (Exception e) {
            LOG.warn("Failed to mark webhook as processed: {}", event.externalEventId(), e);
        }

        return Response.ok(Map.of("received", true)).build();
    }

    private void handleInvoiceEvent(JsonNode invoice, String eventType) {
        String customerId = invoice.path("customer").asText(null);
        String tenantId = resolveTenantFromCustomer(customerId);
        if (tenantId == null) {
            LOG.warn("Cannot resolve tenant for Stripe customer {}", customerId);
            return;
        }

        String externalInvoiceId = invoice.path("id").asText();
        String externalSubId = invoice.path("subscription").asText(null);
        String status = invoice.path("status").asText("draft");
        String currency = invoice.path("currency").asText("usd");
        long amountDue = invoice.path("amount_due").asLong();
        long subtotal = invoice.path("subtotal").asLong();
        long total = invoice.path("total").asLong();
        String hostedUrl = invoice.path("hosted_invoice_url").asText(null);
        String pdfUrl = invoice.path("invoice_pdf").asText(null);
        Instant periodStart = epochToInstant(invoice.path("period_start").asLong(0));
        Instant periodEnd = epochToInstant(invoice.path("period_end").asLong(0));
        Instant dueDate = epochToInstant(invoice.path("due_date").asLong(0));

        cqrs.billingCommandService()
                .recordInvoice(
                        tenantId,
                        externalInvoiceId,
                        externalSubId,
                        status,
                        currency,
                        amountDue,
                        subtotal,
                        total,
                        hostedUrl,
                        pdfUrl,
                        periodStart,
                        periodEnd,
                        dueDate);

        LOG.info("Invoice {} ({}) recorded for tenant {}", externalInvoiceId, status, tenantId);
    }

    private void handleInvoicePaid(JsonNode invoice) {
        String customerId = invoice.path("customer").asText(null);
        String tenantId = resolveTenantFromCustomer(customerId);
        if (tenantId == null) return;

        String externalInvoiceId = invoice.path("id").asText();
        long amountPaid = invoice.path("amount_paid").asLong();
        Instant paidAt = Instant.now();

        cqrs.billingCommandService()
                .recordInvoicePaid(tenantId, externalInvoiceId, amountPaid, paidAt);
        LOG.info("Invoice {} paid for tenant {}", externalInvoiceId, tenantId);
    }

    private void handlePaymentMethodAttached(JsonNode pm) {
        String customerId = pm.path("customer").asText(null);
        String tenantId = resolveTenantFromCustomer(customerId);
        if (tenantId == null) return;

        String pmId = pm.path("id").asText();
        String type = pm.path("type").asText("card");
        JsonNode card = pm.path("card");
        String brand = card.path("brand").asText(null);
        String last4 = card.path("last4").asText(null);
        int expMonth = card.path("exp_month").asInt(0);
        int expYear = card.path("exp_year").asInt(0);

        cqrs.billingCommandService()
                .attachPaymentMethod(tenantId, pmId, type, brand, last4, expMonth, expYear, false);
        LOG.info("Payment method {} attached via webhook for tenant {}", pmId, tenantId);
    }

    private void handlePaymentMethodDetached(JsonNode pm) {
        String pmId = pm.path("id").asText();
        String customerId = pm.path("customer").asText(null);
        String tenantId = resolveTenantFromCustomer(customerId);
        if (tenantId == null) {
            LOG.warn("Cannot resolve tenant for detached payment method {}", pmId);
            return;
        }
        cqrs.billingCommandService().detachPaymentMethod(tenantId, pmId);
        LOG.info("Payment method {} detached via webhook for tenant {}", pmId, tenantId);
    }

    private String resolveTenantFromCustomer(String stripeCustomerId) {
        if (stripeCustomerId == null) return null;
        var profile =
                cqrs.billingQueryService().getBillingProfileByExternalCustomerId(stripeCustomerId);
        if (profile == null) {
            LOG.warn("No tenant found for Stripe customer {}", stripeCustomerId);
            return null;
        }
        return profile.tenantId();
    }

    private static Instant epochToInstant(long epoch) {
        return epoch > 0 ? Instant.ofEpochSecond(epoch) : null;
    }
}
