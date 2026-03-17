package com.koostack.cveil.core.platform.billing;

/**
 * Event type constants for billing platform domain events.
 *
 * <p>Each constant corresponds to a mutation published to the platform event store by the {@link
 * BillingCommandService}. Projections consume these event types to materialize the billing read
 * model.
 */
public final class BillingEventTypes {

    private BillingEventTypes() {}

    // -- Billing profile ------------------------------------------------------
    public static final String BILLING_PROFILE_CREATED = "billing.profile.created";
    public static final String BILLING_EMAIL_UPDATED = "billing.profile.email-updated";
    public static final String BILLING_TAX_ID_UPDATED = "billing.profile.tax-id-updated";
    public static final String PAYMENT_PROCESSOR_SET = "billing.profile.payment-processor-set";
    public static final String PAYMENT_METHOD_STATUS_UPDATED =
            "billing.profile.payment-method-status-updated";

    // -- Subscription ---------------------------------------------------------
    public static final String SUBSCRIPTION_CREATED = "billing.subscription.created";
    public static final String SUBSCRIPTION_PLAN_CHANGED = "billing.subscription.plan-changed";
    public static final String SUBSCRIPTION_CANCELLED = "billing.subscription.cancelled";
    public static final String SUBSCRIPTION_RENEWED = "billing.subscription.renewed";

    // -- Payment methods ------------------------------------------------------
    public static final String PAYMENT_METHOD_ATTACHED = "billing.payment-method.attached";
    public static final String PAYMENT_METHOD_DETACHED = "billing.payment-method.detached";
    public static final String PAYMENT_METHOD_DEFAULT_CHANGED =
            "billing.payment-method.default-changed";

    // -- Invoices -------------------------------------------------------------
    public static final String INVOICE_CREATED = "billing.invoice.created";
    public static final String INVOICE_UPDATED = "billing.invoice.updated";
    public static final String INVOICE_PAID = "billing.invoice.paid";

    // -- Usage ----------------------------------------------------------------
    public static final String USAGE_REPORTED = "billing.usage.reported";
}
