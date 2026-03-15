package com.koostack.cveil.platform.billing.jooq;

import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.event.DomainEvent;
import com.koostack.cveil.core.event.EventService;
import com.koostack.cveil.core.event.models.PublishDomainEventInput;
import com.koostack.cveil.core.models.AggregateKey;
import com.koostack.cveil.core.platform.billing.BillingCommandService;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.cveil.core.platform.billing.events.*;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqBillingCommandService implements BillingCommandService<JooqBillingCommandConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqBillingCommandService.class);

    private EventService<?> eventService;

    @Override
    public String getName() {
        return "jooq-billing-command";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqBillingCommandConfig> getConfigurationType() {
        return JooqBillingCommandConfig.class;
    }

    @Override
    public void configure(JooqBillingCommandConfig config) {
        this.eventService = config.eventService();
        LOG.info("JooqBillingCommandService configured");
    }

    // -- Billing profile commands ---------------------------------------------

    @Override
    public void createBillingProfile(String tenantId, String billingEmail) {
        var payload = new BillingProfileCreatedEvent(tenantId, billingEmail);
        publishEvent(BillingEventTypes.BILLING_PROFILE_CREATED, tenantId, payload);
    }

    @Override
    public void updateBillingEmail(String tenantId, String newEmail) {
        var payload = new BillingEmailUpdatedEvent(tenantId, newEmail);
        publishEvent(BillingEventTypes.BILLING_EMAIL_UPDATED, tenantId, payload);
    }

    @Override
    public void updateTaxId(String tenantId, String taxId) {
        var payload = new BillingTaxIdUpdatedEvent(tenantId, taxId);
        publishEvent(BillingEventTypes.BILLING_TAX_ID_UPDATED, tenantId, payload);
    }

    @Override
    public void setPaymentProcessor(String tenantId, String processor, String customerId) {
        var payload = new PaymentProcessorSetEvent(tenantId, processor, customerId);
        publishEvent(BillingEventTypes.PAYMENT_PROCESSOR_SET, tenantId, payload);
    }

    @Override
    public void updatePaymentMethodStatus(String tenantId, String status) {
        var payload = new PaymentMethodStatusUpdatedEvent(tenantId, status);
        publishEvent(BillingEventTypes.PAYMENT_METHOD_STATUS_UPDATED, tenantId, payload);
    }

    // -- Subscription commands ------------------------------------------------

    @Override
    public void createSubscription(
            String tenantId,
            String planTier,
            int evalLimit,
            int subjectLimit,
            int burstLimit,
            String externalSubscriptionId,
            Instant periodStart,
            Instant periodEnd) {
        var payload =
                new SubscriptionCreatedEvent(
                        tenantId,
                        planTier,
                        evalLimit,
                        subjectLimit,
                        burstLimit,
                        externalSubscriptionId,
                        periodStart,
                        periodEnd);
        publishEvent(BillingEventTypes.SUBSCRIPTION_CREATED, tenantId, payload);
    }

    @Override
    public void changeSubscriptionPlan(
            String tenantId,
            String oldPlanTier,
            String newPlanTier,
            int evalLimit,
            int subjectLimit,
            int burstLimit) {
        var payload =
                new SubscriptionPlanChangedEvent(
                        tenantId, oldPlanTier, newPlanTier, evalLimit, subjectLimit, burstLimit);
        publishEvent(BillingEventTypes.SUBSCRIPTION_PLAN_CHANGED, tenantId, payload);
    }

    @Override
    public void cancelSubscription(String tenantId) {
        var payload = new SubscriptionCancelledEvent(tenantId);
        publishEvent(BillingEventTypes.SUBSCRIPTION_CANCELLED, tenantId, payload);
    }

    @Override
    public void renewSubscription(String tenantId, Instant periodStart, Instant periodEnd) {
        var payload = new SubscriptionRenewedEvent(tenantId, periodStart, periodEnd);
        publishEvent(BillingEventTypes.SUBSCRIPTION_RENEWED, tenantId, payload);
    }

    // -- Payment method commands ----------------------------------------------

    @Override
    public void attachPaymentMethod(
            String tenantId,
            String externalPaymentMethodId,
            String type,
            String brand,
            String last4,
            int expMonth,
            int expYear,
            boolean isDefault) {
        var payload =
                new PaymentMethodAttachedEvent(
                        tenantId,
                        externalPaymentMethodId,
                        type,
                        brand,
                        last4,
                        expMonth,
                        expYear,
                        isDefault);
        publishEvent(BillingEventTypes.PAYMENT_METHOD_ATTACHED, tenantId, payload);
    }

    @Override
    public void detachPaymentMethod(String tenantId, String externalPaymentMethodId) {
        var payload = new PaymentMethodDetachedEvent(tenantId, externalPaymentMethodId);
        publishEvent(BillingEventTypes.PAYMENT_METHOD_DETACHED, tenantId, payload);
    }

    @Override
    public void setDefaultPaymentMethod(String tenantId, String externalPaymentMethodId) {
        var payload = new PaymentMethodDefaultChangedEvent(tenantId, externalPaymentMethodId);
        publishEvent(BillingEventTypes.PAYMENT_METHOD_DEFAULT_CHANGED, tenantId, payload);
    }

    // -- Invoice commands -----------------------------------------------------

    @Override
    public void recordInvoice(
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
            Instant dueDate) {
        var payload =
                new InvoiceCreatedEvent(
                        tenantId,
                        externalInvoiceId,
                        externalSubscriptionId,
                        status,
                        currency,
                        amountDue,
                        subtotal,
                        total,
                        hostedInvoiceUrl,
                        invoicePdfUrl,
                        periodStart,
                        periodEnd,
                        dueDate);
        publishEvent(BillingEventTypes.INVOICE_CREATED, tenantId, payload);
    }

    @Override
    public void recordInvoicePaid(
            String tenantId, String externalInvoiceId, long amountPaid, Instant paidAt) {
        var payload = new InvoicePaidEvent(tenantId, externalInvoiceId, amountPaid, paidAt);
        publishEvent(BillingEventTypes.INVOICE_PAID, tenantId, payload);
    }

    @Override
    public void recordInvoiceUpdated(
            String tenantId,
            String externalInvoiceId,
            String status,
            long amountDue,
            long amountPaid,
            long amountRemaining,
            long total,
            String hostedInvoiceUrl,
            String invoicePdfUrl,
            Instant paidAt) {
        var payload =
                new InvoiceUpdatedEvent(
                        tenantId,
                        externalInvoiceId,
                        status,
                        amountDue,
                        amountPaid,
                        amountRemaining,
                        total,
                        hostedInvoiceUrl,
                        invoicePdfUrl,
                        paidAt);
        publishEvent(BillingEventTypes.INVOICE_UPDATED, tenantId, payload);
    }

    // -- Usage commands -------------------------------------------------------

    @Override
    public void recordUsageReported(
            String tenantId, String metricName, long quantity, Instant reportedAt) {
        var payload = new UsageReportedEvent(tenantId, metricName, quantity, reportedAt);
        publishEvent(BillingEventTypes.USAGE_REPORTED, tenantId, payload);
    }

    // -- Internal helpers -----------------------------------------------------

    private void publishEvent(String eventType, String tenantId, DomainEvent payload) {
        UUID aggregateId = BillingProfileAggregate.deriveId(tenantId);
        AggregateKey key =
                new AggregateKey(
                        aggregateId,
                        BillingProfileAggregate.AGGREGATE_TYPE,
                        PlatformConstants.PLATFORM_TENANT_ID);
        eventService.publishEvent(
                PublishDomainEventInput.of(
                        PlatformConstants.PLATFORM_TENANT_ID, eventType, key, payload));
    }
}
