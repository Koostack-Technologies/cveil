package com.koostack.cveil.core.platform.billing;

import com.koostack.cveil.core.spi.Plugin;
import java.time.Instant;

/**
 * Command service for the billing platform domain.
 *
 * <p>All commands publish semantic domain events to the platform event store. The read model is
 * materialized by the corresponding {@link BillingProjection}.
 */
public interface BillingCommandService<T> extends Plugin<T> {

    // -- Billing profile ------------------------------------------------------

    /** Create a billing profile for the given tenant. */
    void createBillingProfile(String tenantId, String billingEmail);

    /** Update the billing email address for a tenant's profile. */
    void updateBillingEmail(String tenantId, String newEmail);

    /** Update the tax identifier on a tenant's billing profile. */
    void updateTaxId(String tenantId, String taxId);

    /** Set the payment processor and external customer identifier. */
    void setPaymentProcessor(String tenantId, String processor, String customerId);

    /** Update the payment method status (e.g. "valid", "expired", "none"). */
    void updatePaymentMethodStatus(String tenantId, String status);

    // -- Subscription ---------------------------------------------------------

    /** Create an initial subscription for a tenant. */
    void createSubscription(
            String tenantId,
            String planTier,
            int evalLimit,
            int subjectLimit,
            int burstLimit,
            String externalSubscriptionId,
            Instant periodStart,
            Instant periodEnd);

    /** Change the subscription plan tier and associated limits. */
    void changeSubscriptionPlan(
            String tenantId,
            String oldPlanTier,
            String newPlanTier,
            int evalLimit,
            int subjectLimit,
            int burstLimit);

    /** Cancel the current subscription. */
    void cancelSubscription(String tenantId);

    /** Renew the subscription with new period boundaries. */
    void renewSubscription(String tenantId, Instant periodStart, Instant periodEnd);

    // -- Payment methods ------------------------------------------------------

    /** Record a payment method attachment to a tenant's billing profile. */
    void attachPaymentMethod(
            String tenantId,
            String externalPaymentMethodId,
            String type,
            String brand,
            String last4,
            int expMonth,
            int expYear,
            boolean isDefault);

    /** Record a payment method detachment from a tenant's billing profile. */
    void detachPaymentMethod(String tenantId, String externalPaymentMethodId);

    /** Record a change to the default payment method. */
    void setDefaultPaymentMethod(String tenantId, String externalPaymentMethodId);

    // -- Invoices -------------------------------------------------------------

    /** Record an invoice (typically from a payment processor webhook). */
    void recordInvoice(
            String tenantId,
            String externalInvoiceId,
            String externalSubscriptionId,
            String status,
            String currency,
            long amountDue,
            long subtotal,
            long total,
            String hostedInvoiceUrl,
            String invoicePdfUrl,
            Instant periodStart,
            Instant periodEnd,
            Instant dueDate);

    /** Record that an invoice has been paid. */
    void recordInvoicePaid(
            String tenantId, String externalInvoiceId, long amountPaid, Instant paidAt);

    /** Record an invoice status/amount update (e.g. from a webhook). */
    void recordInvoiceUpdated(
            String tenantId,
            String externalInvoiceId,
            String status,
            long amountDue,
            long amountPaid,
            long amountRemaining,
            long total,
            String hostedInvoiceUrl,
            String invoicePdfUrl,
            Instant paidAt);

    // -- Usage ----------------------------------------------------------------

    /** Record that metered usage was reported to the payment processor. */
    void recordUsageReported(String tenantId, String metricName, long quantity, Instant reportedAt);
}
