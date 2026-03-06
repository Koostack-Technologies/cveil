package com.koostack.operio.core.platform.billing;

import com.koostack.operio.core.spi.Plugin;

import java.time.Instant;

/**
 * Command service for the billing platform domain.
 *
 * <p>All commands publish semantic domain events to the platform event
 * store. The read model is materialized by the corresponding
 * {@link BillingProjection}.
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
    void createSubscription(String tenantId, String planTier,
                            int evalLimit, int subjectLimit, int burstLimit);

    /** Change the subscription plan tier and associated limits. */
    void changeSubscriptionPlan(String tenantId, String oldPlanTier, String newPlanTier,
                                int evalLimit, int subjectLimit, int burstLimit);

    /** Cancel the current subscription. */
    void cancelSubscription(String tenantId);

    /** Renew the subscription with new period boundaries. */
    void renewSubscription(String tenantId, Instant periodStart, Instant periodEnd);
}
