package com.koostack.cveil.rest.dto;

import com.koostack.cveil.core.platform.billing.models.InvoiceInfo;
import com.koostack.cveil.core.platform.billing.models.InvoiceLineItemInfo;
import com.koostack.cveil.core.platform.billing.models.PaymentMethodInfo;
import com.koostack.cveil.core.platform.billing.models.SubscriptionInfo;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public final class BillingDtos {
    private BillingDtos() {}

    // -- Setup Intent ---------------------------------------------------------

    @Schema(description = "Response containing a Stripe SetupIntent client secret")
    public record SetupIntentResponse(
            @Schema(description = "SetupIntent ID", example = "seti_1234") String setupIntentId,
            @Schema(description = "Client secret for Stripe.js confirmCardSetup")
                    String clientSecret) {}

    // -- Payment Methods ------------------------------------------------------

    @Schema(description = "Payment method summary (no sensitive card data)")
    public record PaymentMethodResponse(
            String id,
            String externalPaymentMethodId,
            String type,
            String cardBrand,
            String cardLast4,
            int cardExpMonth,
            int cardExpYear,
            boolean isDefault,
            String status,
            Instant createdAt) {
        public static PaymentMethodResponse fromModel(PaymentMethodInfo info) {
            return new PaymentMethodResponse(
                    info.id(),
                    info.externalPaymentMethodId(),
                    info.type(),
                    info.cardBrand(),
                    info.cardLast4(),
                    info.cardExpMonth(),
                    info.cardExpYear(),
                    info.isDefault(),
                    info.status(),
                    info.createdAt());
        }
    }

    @Schema(description = "Request to set a payment method as default")
    public record SetDefaultPaymentMethodRequest(
            @Schema(description = "External payment method ID (e.g. pm_xxx)", required = true)
                    String paymentMethodId) {}

    // -- Invoices -------------------------------------------------------------

    @Schema(description = "Invoice summary")
    public record InvoiceResponse(
            String id,
            String externalInvoiceId,
            String status,
            String currency,
            long amountDue,
            long amountPaid,
            long total,
            String hostedInvoiceUrl,
            String invoicePdfUrl,
            Instant periodStart,
            Instant periodEnd,
            Instant paidAt,
            Instant createdAt,
            List<InvoiceLineItemResponse> lineItems) {
        public static InvoiceResponse fromModel(InvoiceInfo info) {
            List<InvoiceLineItemResponse> items =
                    info.lineItems() != null
                            ? info.lineItems().stream()
                                    .map(InvoiceLineItemResponse::fromModel)
                                    .toList()
                            : List.of();
            return new InvoiceResponse(
                    info.id(),
                    info.externalInvoiceId(),
                    info.status(),
                    info.currency(),
                    info.amountDue(),
                    info.amountPaid(),
                    info.total(),
                    info.hostedInvoiceUrl(),
                    info.invoicePdfUrl(),
                    info.periodStart(),
                    info.periodEnd(),
                    info.paidAt(),
                    info.createdAt(),
                    items);
        }
    }

    @Schema(description = "Invoice line item detail")
    public record InvoiceLineItemResponse(
            String id,
            String description,
            long amount,
            String currency,
            int quantity,
            long unitAmount,
            boolean proration) {
        public static InvoiceLineItemResponse fromModel(InvoiceLineItemInfo info) {
            return new InvoiceLineItemResponse(
                    info.id(),
                    info.description(),
                    info.amount(),
                    info.currency(),
                    info.quantity(),
                    info.unitAmount(),
                    info.proration());
        }
    }

    // -- Subscription ----------------------------------------------------------

    @Schema(description = "Current subscription details")
    public record SubscriptionResponse(
            String planTier,
            String status,
            int evalLimit,
            int subjectLimit,
            int burstLimit,
            boolean cancelAtPeriodEnd,
            Instant periodStart,
            Instant periodEnd) {
        public static SubscriptionResponse fromModel(SubscriptionInfo s) {
            return new SubscriptionResponse(
                    s.planTier(),
                    s.status(),
                    s.evalLimit(),
                    s.subjectLimit(),
                    s.burstLimit(),
                    s.cancelAtPeriodEnd(),
                    s.periodStart(),
                    s.periodEnd());
        }
    }

    @Schema(description = "Available plan details")
    public record PlanResponse(
            String tier,
            String displayName,
            String description,
            int priceInCents,
            int evalLimit,
            int subjectLimit,
            int burstLimit,
            boolean contactSales,
            List<String> features) {}

    @Schema(description = "Request to subscribe to a plan")
    public record CreateSubscriptionRequest(
            @Schema(description = "Plan tier to subscribe to", required = true) String planTier) {}

    @Schema(description = "Request to change subscription plan")
    public record ChangeSubscriptionRequest(
            @Schema(description = "New plan tier", required = true) String planTier) {}

    // -- Stripe publishable key -----------------------------------------------

    @Schema(description = "Public Stripe configuration for frontend initialization")
    public record StripeConfigResponse(
            @Schema(description = "Stripe publishable key for Stripe.js", example = "pk_test_...")
                    String publishableKey) {}
}
