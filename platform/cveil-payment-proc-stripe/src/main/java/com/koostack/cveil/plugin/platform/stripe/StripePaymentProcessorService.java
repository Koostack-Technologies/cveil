package com.koostack.cveil.plugin.platform.stripe;

import com.koostack.cveil.core.exception.CveilServiceException;
import com.koostack.cveil.core.platform.billing.PaymentProcessorService;
import com.koostack.cveil.core.platform.billing.models.InvoiceDetail;
import com.koostack.cveil.core.platform.billing.models.InvoiceLineItemDetail;
import com.koostack.cveil.core.platform.billing.models.PaymentMethodDetail;
import com.koostack.cveil.core.platform.billing.models.ProductPriceMapping;
import com.koostack.cveil.core.platform.billing.models.SetupIntentDetail;
import com.koostack.cveil.core.platform.billing.models.SubscriptionDetail;
import com.koostack.cveil.core.platform.billing.models.WebhookEvent;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.net.Webhook;
import com.stripe.param.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stripe-backed payment processor — wraps the Stripe Java SDK.
 *
 * <p>Follows the same pattern as {@code KeycloakIdpProvider}: a thread-safe singleton that
 * implements the processor-agnostic SPI defined in {@code cveil-core}.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is thread-safe. The Stripe SDK uses a global API key and thread-safe HTTP client
 * internally.
 */
public class StripePaymentProcessorService
        implements PaymentProcessorService<StripePaymentProcessorConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(StripePaymentProcessorService.class);

    private StripePaymentProcessorConfig config;

    public StripePaymentProcessorService() {}

    public StripePaymentProcessorService(StripePaymentProcessorConfig config) {
        configure(config);
    }

    // -------------------------------------------------------------------
    // Plugin SPI
    // -------------------------------------------------------------------

    @Override
    public String getName() {
        return "stripe";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<StripePaymentProcessorConfig> getConfigurationType() {
        return StripePaymentProcessorConfig.class;
    }

    @Override
    public void configure(StripePaymentProcessorConfig config) {
        this.config = config;
        // NOTE: Stripe.apiKey is a JVM-wide static. This is safe for single-account deployments.
        // For multi-account scenarios, migrate to per-request RequestOptions.
        Stripe.apiKey = config.secretKey();
        String maskedKey =
                config.secretKey().length() >= 4
                        ? config.secretKey().substring(config.secretKey().length() - 4)
                        : "****";
        LOG.info("Stripe payment processor configured (key ending ...{})", maskedKey);
    }

    @Override
    public boolean isReady() {
        if (config == null) {
            return false;
        }
        try {
            // Lightweight connectivity check
            com.stripe.model.Balance.retrieve();
            return true;
        } catch (Exception e) {
            LOG.debug("Stripe health check failed: {}", e.getMessage());
            return false;
        }
    }

    /** Returns the publishable key for frontend Stripe Elements initialization. */
    @Override
    public String getPublishableKey() {
        return config.publishableKey();
    }

    // -------------------------------------------------------------------
    // Customer lifecycle
    // -------------------------------------------------------------------

    @Override
    public String createCustomer(String tenantId, String email, String name) {
        try {
            CustomerCreateParams params =
                    CustomerCreateParams.builder()
                            .setEmail(email)
                            .setName(name)
                            .putMetadata("tenant_id", tenantId)
                            .build();
            Customer customer = Customer.create(params);
            LOG.info("Created Stripe customer {} for tenant {}", customer.getId(), tenantId);
            return customer.getId();
        } catch (StripeException e) {
            throw new CveilServiceException(
                    "Failed to create Stripe customer: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateCustomer(String externalCustomerId, String email, String name) {
        try {
            Customer customer = Customer.retrieve(externalCustomerId);
            CustomerUpdateParams params =
                    CustomerUpdateParams.builder().setEmail(email).setName(name).build();
            customer.update(params);
            LOG.debug("Updated Stripe customer {}", externalCustomerId);
        } catch (StripeException e) {
            throw new CveilServiceException(
                    "Failed to update Stripe customer: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------
    // Payment methods
    // -------------------------------------------------------------------

    @Override
    public SetupIntentDetail createSetupIntent(String externalCustomerId) {
        try {
            SetupIntentCreateParams params =
                    SetupIntentCreateParams.builder()
                            .setCustomer(externalCustomerId)
                            .addPaymentMethodType("card")
                            .build();
            SetupIntent intent = SetupIntent.create(params);
            LOG.debug("Created SetupIntent {} for customer {}", intent.getId(), externalCustomerId);
            return new SetupIntentDetail(intent.getId(), intent.getClientSecret());
        } catch (StripeException e) {
            throw new CveilServiceException("Failed to create setup intent: " + e.getMessage(), e);
        }
    }

    @Override
    public PaymentMethodDetail attachPaymentMethod(
            String externalCustomerId, String paymentMethodToken) {
        try {
            PaymentMethod pm = PaymentMethod.retrieve(paymentMethodToken);
            PaymentMethodAttachParams params =
                    PaymentMethodAttachParams.builder().setCustomer(externalCustomerId).build();
            pm = pm.attach(params);
            LOG.info("Attached payment method {} to customer {}", pm.getId(), externalCustomerId);
            return toPaymentMethodDetail(pm, false);
        } catch (StripeException e) {
            throw new CveilServiceException(
                    "Failed to attach payment method: " + e.getMessage(), e);
        }
    }

    @Override
    public void detachPaymentMethod(String externalPaymentMethodId) {
        try {
            PaymentMethod pm = PaymentMethod.retrieve(externalPaymentMethodId);
            pm.detach();
            LOG.info("Detached payment method {}", externalPaymentMethodId);
        } catch (StripeException e) {
            throw new CveilServiceException(
                    "Failed to detach payment method: " + e.getMessage(), e);
        }
    }

    @Override
    public void setDefaultPaymentMethod(String externalCustomerId, String externalPaymentMethodId) {
        try {
            Customer customer = Customer.retrieve(externalCustomerId);
            CustomerUpdateParams params =
                    CustomerUpdateParams.builder()
                            .setInvoiceSettings(
                                    CustomerUpdateParams.InvoiceSettings.builder()
                                            .setDefaultPaymentMethod(externalPaymentMethodId)
                                            .build())
                            .build();
            customer.update(params);
            LOG.info(
                    "Set default payment method {} for customer {}",
                    externalPaymentMethodId,
                    externalCustomerId);
        } catch (StripeException e) {
            throw new CveilServiceException(
                    "Failed to set default payment method: " + e.getMessage(), e);
        }
    }

    @Override
    public List<PaymentMethodDetail> listPaymentMethods(String externalCustomerId) {
        try {
            // Retrieve the customer to find the default payment method
            Customer customer = Customer.retrieve(externalCustomerId);
            String defaultPmId = null;
            if (customer.getInvoiceSettings() != null) {
                defaultPmId = customer.getInvoiceSettings().getDefaultPaymentMethod();
            }

            PaymentMethodListParams params =
                    PaymentMethodListParams.builder()
                            .setCustomer(externalCustomerId)
                            .setType(PaymentMethodListParams.Type.CARD)
                            .build();
            PaymentMethodCollection collection = PaymentMethod.list(params);

            List<PaymentMethodDetail> result = new ArrayList<>();
            for (PaymentMethod pm : collection.autoPagingIterable()) {
                result.add(toPaymentMethodDetail(pm, pm.getId().equals(defaultPmId)));
            }
            return result;
        } catch (StripeException e) {
            throw new CveilServiceException("Failed to list payment methods: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------
    // Subscriptions
    // -------------------------------------------------------------------

    @Override
    public SubscriptionDetail createSubscription(
            String externalCustomerId, String externalPriceId) {
        try {
            SubscriptionCreateParams params =
                    SubscriptionCreateParams.builder()
                            .setCustomer(externalCustomerId)
                            .addItem(
                                    SubscriptionCreateParams.Item.builder()
                                            .setPrice(externalPriceId)
                                            .build())
                            .setPaymentBehavior(
                                    SubscriptionCreateParams.PaymentBehavior.DEFAULT_INCOMPLETE)
                            .addExpand("latest_invoice.payment_intent")
                            .build();
            Subscription sub = Subscription.create(params);
            LOG.info("Created subscription {} for customer {}", sub.getId(), externalCustomerId);
            return toSubscriptionDetail(sub);
        } catch (StripeException e) {
            throw new CveilServiceException("Failed to create subscription: " + e.getMessage(), e);
        }
    }

    @Override
    public SubscriptionDetail updateSubscription(
            String externalSubscriptionId, String newExternalPriceId) {
        try {
            Subscription sub = Subscription.retrieve(externalSubscriptionId);
            List<SubscriptionItem> items = sub.getItems().getData();
            if (items.isEmpty()) {
                throw new CveilServiceException(
                        "Subscription " + externalSubscriptionId + " has no items to update");
            }
            String itemId = items.get(0).getId();

            SubscriptionUpdateParams params =
                    SubscriptionUpdateParams.builder()
                            .addItem(
                                    SubscriptionUpdateParams.Item.builder()
                                            .setId(itemId)
                                            .setPrice(newExternalPriceId)
                                            .build())
                            .setProrationBehavior(
                                    SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
                            .build();
            sub = sub.update(params);
            LOG.info(
                    "Updated subscription {} to price {}",
                    externalSubscriptionId,
                    newExternalPriceId);
            return toSubscriptionDetail(sub);
        } catch (StripeException e) {
            throw new CveilServiceException("Failed to update subscription: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelSubscription(String externalSubscriptionId, boolean atPeriodEnd) {
        try {
            Subscription sub = Subscription.retrieve(externalSubscriptionId);
            if (atPeriodEnd) {
                SubscriptionUpdateParams params =
                        SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build();
                sub.update(params);
                LOG.info("Subscription {} set to cancel at period end", externalSubscriptionId);
            } else {
                sub.cancel();
                LOG.info("Subscription {} cancelled immediately", externalSubscriptionId);
            }
        } catch (StripeException e) {
            throw new CveilServiceException("Failed to cancel subscription: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------
    // Invoices
    // -------------------------------------------------------------------

    @Override
    public List<InvoiceDetail> listInvoices(String externalCustomerId, int limit) {
        try {
            InvoiceListParams params =
                    InvoiceListParams.builder()
                            .setCustomer(externalCustomerId)
                            .setLimit((long) limit)
                            .build();
            InvoiceCollection collection = Invoice.list(params);

            List<InvoiceDetail> result = new ArrayList<>();
            for (Invoice inv : collection.getData()) {
                result.add(toInvoiceDetail(inv));
            }
            return result;
        } catch (StripeException e) {
            throw new CveilServiceException("Failed to list invoices: " + e.getMessage(), e);
        }
    }

    @Override
    public InvoiceDetail getInvoice(String externalInvoiceId) {
        try {
            Invoice inv = Invoice.retrieve(externalInvoiceId);
            return toInvoiceDetail(inv);
        } catch (StripeException e) {
            throw new CveilServiceException("Failed to retrieve invoice: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------
    // Usage reporting
    // -------------------------------------------------------------------

    @Override
    public void reportUsage(String externalSubscriptionItemId, long quantity, Instant timestamp) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("quantity", quantity);
            params.put("timestamp", timestamp.getEpochSecond());
            params.put("action", "increment");

            // UsageRecord is created on the SubscriptionItem
            SubscriptionItem item = SubscriptionItem.retrieve(externalSubscriptionItemId);
            UsageRecord.createOnSubscriptionItem(item.getId(), params, null);
            LOG.debug(
                    "Reported usage: {} units on subscription item {}",
                    quantity,
                    externalSubscriptionItemId);
        } catch (StripeException e) {
            throw new CveilServiceException("Failed to report usage: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------
    // Product / Price sync
    // -------------------------------------------------------------------

    @Override
    public ProductPriceMapping syncProduct(
            String planId, String displayName, long unitAmount, String interval) {
        try {
            // Search for existing product by metadata
            ProductSearchParams searchParams =
                    ProductSearchParams.builder()
                            .setQuery("metadata['plan_id']:'" + planId + "'")
                            .build();
            ProductSearchResult searchResult = Product.search(searchParams);

            Product product;
            if (!searchResult.getData().isEmpty()) {
                product = searchResult.getData().get(0);
                LOG.debug("Found existing Stripe product {} for plan {}", product.getId(), planId);
            } else {
                ProductCreateParams createParams =
                        ProductCreateParams.builder()
                                .setName(displayName)
                                .putMetadata("plan_id", planId)
                                .build();
                product = Product.create(createParams);
                LOG.info("Created Stripe product {} for plan {}", product.getId(), planId);
            }

            // Search for existing price on the product
            PriceListParams priceListParams =
                    PriceListParams.builder().setProduct(product.getId()).setActive(true).build();
            PriceCollection prices = Price.list(priceListParams);

            Price price;
            if (!prices.getData().isEmpty()) {
                price = prices.getData().get(0);
                LOG.debug("Found existing Stripe price {} for plan {}", price.getId(), planId);
            } else {
                PriceCreateParams priceParams =
                        PriceCreateParams.builder()
                                .setProduct(product.getId())
                                .setUnitAmount(unitAmount)
                                .setCurrency("usd")
                                .setRecurring(
                                        PriceCreateParams.Recurring.builder()
                                                .setInterval(
                                                        PriceCreateParams.Recurring.Interval
                                                                .valueOf(interval.toUpperCase()))
                                                .build())
                                .build();
                price = Price.create(priceParams);
                LOG.info("Created Stripe price {} for plan {}", price.getId(), planId);
            }

            return new ProductPriceMapping(product.getId(), price.getId());
        } catch (StripeException e) {
            throw new CveilServiceException(
                    "Failed to sync product for plan " + planId + ": " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------
    // Webhook verification
    // -------------------------------------------------------------------

    @Override
    public WebhookEvent verifyAndParseWebhook(String payload, String signatureHeader) {
        try {
            Event event = Webhook.constructEvent(payload, signatureHeader, config.webhookSecret());
            return new WebhookEvent(event.getId(), event.getType(), payload);
        } catch (SignatureVerificationException e) {
            throw new CveilServiceException(
                    "Invalid Stripe webhook signature: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------

    private static PaymentMethodDetail toPaymentMethodDetail(PaymentMethod pm, boolean isDefault) {
        PaymentMethod.Card card = pm.getCard();
        return new PaymentMethodDetail(
                pm.getId(),
                pm.getType(),
                card != null ? card.getBrand() : null,
                card != null ? card.getLast4() : null,
                card != null && card.getExpMonth() != null ? card.getExpMonth().intValue() : 0,
                card != null && card.getExpYear() != null ? card.getExpYear().intValue() : 0,
                isDefault);
    }

    private static SubscriptionDetail toSubscriptionDetail(Subscription sub) {
        List<SubscriptionDetail.SubscriptionItemDetail> items = new ArrayList<>();
        if (sub.getItems() != null) {
            for (SubscriptionItem item : sub.getItems().autoPagingIterable()) {
                items.add(
                        new SubscriptionDetail.SubscriptionItemDetail(
                                item.getId(),
                                item.getPrice().getId(),
                                item.getQuantity() != null ? item.getQuantity() : 1));
            }
        }
        return new SubscriptionDetail(
                sub.getId(),
                sub.getCustomer(),
                sub.getStatus(),
                Instant.ofEpochSecond(sub.getCurrentPeriodStart()),
                Instant.ofEpochSecond(sub.getCurrentPeriodEnd()),
                Boolean.TRUE.equals(sub.getCancelAtPeriodEnd()),
                items);
    }

    private static InvoiceDetail toInvoiceDetail(Invoice inv) {
        List<InvoiceLineItemDetail> lineItems = new ArrayList<>();
        if (inv.getLines() != null) {
            for (InvoiceLineItem li : inv.getLines().getData()) {
                lineItems.add(
                        new InvoiceLineItemDetail(
                                li.getId(),
                                li.getDescription(),
                                li.getAmount(),
                                li.getCurrency(),
                                li.getQuantity() != null ? li.getQuantity().intValue() : 0,
                                li.getPrice() != null && li.getPrice().getUnitAmount() != null
                                        ? li.getPrice().getUnitAmount()
                                        : 0,
                                li.getPeriod() != null
                                        ? Instant.ofEpochSecond(li.getPeriod().getStart())
                                        : null,
                                li.getPeriod() != null
                                        ? Instant.ofEpochSecond(li.getPeriod().getEnd())
                                        : null,
                                Boolean.TRUE.equals(li.getProration())));
            }
        }
        return new InvoiceDetail(
                inv.getId(),
                inv.getSubscription(),
                inv.getStatus(),
                inv.getCurrency(),
                inv.getAmountDue(),
                inv.getAmountPaid(),
                inv.getAmountRemaining(),
                inv.getSubtotal(),
                inv.getTax() != null ? inv.getTax() : 0,
                inv.getTotal(),
                inv.getHostedInvoiceUrl(),
                inv.getInvoicePdf(),
                inv.getPeriodStart() != null ? Instant.ofEpochSecond(inv.getPeriodStart()) : null,
                inv.getPeriodEnd() != null ? Instant.ofEpochSecond(inv.getPeriodEnd()) : null,
                inv.getDueDate() != null ? Instant.ofEpochSecond(inv.getDueDate()) : null,
                inv.getStatusTransitions() != null && inv.getStatusTransitions().getPaidAt() != null
                        ? Instant.ofEpochSecond(inv.getStatusTransitions().getPaidAt())
                        : null,
                lineItems);
    }
}
