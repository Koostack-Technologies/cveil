package com.koostack.cveil.core.platform.billing;

import com.koostack.cveil.core.platform.billing.models.InvoiceDetail;
import com.koostack.cveil.core.platform.billing.models.PaymentMethodDetail;
import com.koostack.cveil.core.platform.billing.models.ProductPriceMapping;
import com.koostack.cveil.core.platform.billing.models.SetupIntentDetail;
import com.koostack.cveil.core.platform.billing.models.SubscriptionDetail;
import com.koostack.cveil.core.platform.billing.models.WebhookEvent;
import com.koostack.cveil.core.spi.Plugin;
import java.time.Instant;
import java.util.List;

/**
 * Payment processor abstraction — the core SPI for external payment operations.
 *
 * <p>Mirrors the {@link com.koostack.cveil.core.platform.iam.IdpProvider} pattern: the platform
 * defines a processor-agnostic contract; implementations wrap a specific provider (Stripe,
 * Braintree, Adyen, etc.).
 *
 * <p>The platform defaults to a Stripe implementation, but tenants can override with any processor
 * by configuring a different {@code PaymentProcessorService} via the plugin registry.
 *
 * <h2>Known implementations</h2>
 *
 * <ul>
 *   <li>{@code StripePaymentProcessorService} (cveil-payment-proc-stripe) — wraps the Stripe Java
 *       SDK for customer management, payment methods, subscriptions, invoices, and usage reporting.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>Implementations must be thread-safe. A single instance is shared across all request threads.
 *
 * @param <T> the plugin configuration type
 */
public interface PaymentProcessorService<T> extends Plugin<T> {

    // -- Customer lifecycle ---------------------------------------------------

    /**
     * Create an external customer for the given tenant.
     *
     * @param tenantId the internal tenant identifier (stored as metadata on the external customer)
     * @param email the billing email
     * @param name the customer display name
     * @return the external customer identifier (e.g. {@code cus_xxx} for Stripe)
     */
    String createCustomer(String tenantId, String email, String name);

    /**
     * Update an existing external customer's billing details.
     *
     * @param externalCustomerId the external customer identifier
     * @param email the updated billing email
     * @param name the updated customer display name
     */
    void updateCustomer(String externalCustomerId, String email, String name);

    /**
     * Returns the publishable/public key for frontend SDK initialization.
     *
     * <p>For Stripe this is the publishable key (e.g. {@code pk_test_xxx}). Other processors may
     * have an equivalent public credential.
     *
     * @return the publishable key, or {@code null} if not configured
     */
    String getPublishableKey();

    // -- Payment methods ------------------------------------------------------

    /**
     * Create a setup intent for securely collecting payment details client-side.
     *
     * <p>The returned detail contains the intent ID and client secret, which are passed to the
     * frontend SDK (e.g. Stripe Elements) to tokenize the payment method without sensitive data
     * touching our servers.
     *
     * @param externalCustomerId the external customer identifier
     * @return setup intent detail containing the ID and client secret
     */
    SetupIntentDetail createSetupIntent(String externalCustomerId);

    /**
     * Attach a tokenized payment method to a customer.
     *
     * @param externalCustomerId the external customer identifier
     * @param paymentMethodToken the token from the frontend collection flow
     * @return details of the attached payment method
     */
    PaymentMethodDetail attachPaymentMethod(String externalCustomerId, String paymentMethodToken);

    /**
     * Detach a payment method from a customer.
     *
     * @param externalPaymentMethodId the external payment method identifier
     */
    void detachPaymentMethod(String externalPaymentMethodId);

    /**
     * Set the default payment method for a customer.
     *
     * @param externalCustomerId the external customer identifier
     * @param externalPaymentMethodId the external payment method identifier to set as default
     */
    void setDefaultPaymentMethod(String externalCustomerId, String externalPaymentMethodId);

    /**
     * List all payment methods attached to a customer.
     *
     * @param externalCustomerId the external customer identifier
     * @return list of payment method details (never null)
     */
    List<PaymentMethodDetail> listPaymentMethods(String externalCustomerId);

    // -- Subscriptions --------------------------------------------------------

    /**
     * Create a subscription for a customer.
     *
     * @param externalCustomerId the external customer identifier
     * @param externalPriceId the external price identifier for the plan
     * @return details of the created subscription
     */
    SubscriptionDetail createSubscription(String externalCustomerId, String externalPriceId);

    /**
     * Update an existing subscription's price (upgrade/downgrade).
     *
     * <p>The processor handles proration automatically.
     *
     * @param externalSubscriptionId the external subscription identifier
     * @param newExternalPriceId the new external price identifier
     * @return details of the updated subscription
     */
    SubscriptionDetail updateSubscription(String externalSubscriptionId, String newExternalPriceId);

    /**
     * Cancel a subscription.
     *
     * @param externalSubscriptionId the external subscription identifier
     * @param atPeriodEnd if {@code true}, cancels at the end of the current billing period;
     *     otherwise cancels immediately
     */
    void cancelSubscription(String externalSubscriptionId, boolean atPeriodEnd);

    // -- Invoices -------------------------------------------------------------

    /**
     * List invoices for a customer.
     *
     * @param externalCustomerId the external customer identifier
     * @param limit maximum number of invoices to return
     * @return list of invoice details (never null)
     */
    List<InvoiceDetail> listInvoices(String externalCustomerId, int limit);

    /**
     * Retrieve a single invoice by its external identifier.
     *
     * @param externalInvoiceId the external invoice identifier
     * @return the invoice detail
     */
    InvoiceDetail getInvoice(String externalInvoiceId);

    // -- Usage reporting (metered billing) ------------------------------------

    /**
     * Report usage for a metered subscription item.
     *
     * @param externalSubscriptionItemId the external subscription item identifier
     * @param quantity the usage quantity to report
     * @param timestamp the timestamp for the usage record
     */
    void reportUsage(String externalSubscriptionItemId, long quantity, Instant timestamp);

    // -- Product / Price sync -------------------------------------------------

    /**
     * Ensure a local plan has a corresponding external product and price.
     *
     * <p>If the product/price already exists, returns the existing mapping. Implementations must be
     * idempotent.
     *
     * @param planId the local plan identifier
     * @param displayName the product display name
     * @param unitAmount the price in the smallest currency unit (e.g. cents)
     * @param interval the billing interval (e.g. "month", "year")
     * @return the external product and price identifiers
     */
    ProductPriceMapping syncProduct(
            String planId, String displayName, long unitAmount, String interval);

    // -- Webhook verification -------------------------------------------------

    /**
     * Verify and parse an incoming webhook payload.
     *
     * <p>Implementations must verify the signature to prevent spoofed events. Throws if the
     * signature is invalid.
     *
     * @param payload the raw webhook request body
     * @param signatureHeader the signature header value (e.g. {@code Stripe-Signature})
     * @return the verified and parsed webhook event
     * @throws com.koostack.cveil.core.exception.CveilServiceException if the signature is invalid
     */
    WebhookEvent verifyAndParseWebhook(String payload, String signatureHeader);
}
