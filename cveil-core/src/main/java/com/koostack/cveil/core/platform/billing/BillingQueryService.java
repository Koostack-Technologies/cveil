package com.koostack.cveil.core.platform.billing;

import com.koostack.cveil.core.platform.billing.models.BillingProfileInfo;
import com.koostack.cveil.core.platform.billing.models.InvoiceInfo;
import com.koostack.cveil.core.platform.billing.models.PaymentMethodInfo;
import com.koostack.cveil.core.platform.billing.models.SubscriptionInfo;
import com.koostack.cveil.core.spi.Plugin;
import java.util.List;

/**
 * Query service for the billing platform domain (billing read model).
 *
 * <p>Reads from the materialized {@code platform_billing} tables populated by the {@link
 * BillingProjection}.
 */
public interface BillingQueryService<T> extends Plugin<T> {

    /** Retrieve the billing profile for a tenant, or {@code null} if not found. */
    BillingProfileInfo getBillingProfile(String tenantId);

    /** Retrieve the current subscription for a tenant, or {@code null} if not found. */
    SubscriptionInfo getSubscription(String tenantId);

    /** List all billing profiles. */
    List<BillingProfileInfo> listBillingProfiles();

    // -- Payment methods ------------------------------------------------------

    /** List active payment methods for a tenant. */
    List<PaymentMethodInfo> listPaymentMethods(String tenantId);

    /** Retrieve the default payment method for a tenant, or {@code null} if none. */
    PaymentMethodInfo getDefaultPaymentMethod(String tenantId);

    // -- Invoices -------------------------------------------------------------

    /** List invoices for a tenant (most recent first), with pagination. */
    List<InvoiceInfo> listInvoices(String tenantId, int page, int pageSize);

    /** Retrieve a single invoice with line items, or {@code null} if not found. */
    InvoiceInfo getInvoice(String tenantId, String invoiceId);

    // -- Webhook idempotency --------------------------------------------------

    /** Check if a Stripe webhook event has already been processed. */
    boolean isWebhookEventProcessed(String externalEventId);

    /** Record a Stripe webhook event as processed (idempotency). */
    void markWebhookEventProcessed(String externalEventId, String eventType, String payload);

    // -- Lookup by external customer ID ---------------------------------------

    /** Get billing profile by external customer ID (e.g., Stripe customer ID). */
    BillingProfileInfo getBillingProfileByExternalCustomerId(String externalCustomerId);
}
