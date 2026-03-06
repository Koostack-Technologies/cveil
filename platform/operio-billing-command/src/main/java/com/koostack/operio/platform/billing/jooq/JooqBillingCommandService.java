package com.koostack.operio.platform.billing.jooq;

import com.koostack.operio.core.PlatformConstants;
import com.koostack.operio.core.event.DomainEvent;
import com.koostack.operio.core.event.EventService;
import com.koostack.operio.core.event.models.PublishDomainEventInput;
import com.koostack.operio.core.models.AggregateKey;
import com.koostack.operio.core.platform.billing.BillingCommandService;
import com.koostack.operio.core.platform.billing.BillingEventTypes;
import com.koostack.operio.core.platform.billing.aggregates.BillingProfileAggregate;
import com.koostack.operio.core.platform.billing.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

public class JooqBillingCommandService implements BillingCommandService<JooqBillingCommandConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqBillingCommandService.class);

    private EventService<?> eventService;

    @Override public String getName() { return "jooq-billing-command"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<JooqBillingCommandConfig> getConfigurationType() { return JooqBillingCommandConfig.class; }

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
    public void createSubscription(String tenantId, String planTier,
                                   int evalLimit, int subjectLimit, int burstLimit) {
        var payload = new SubscriptionCreatedEvent(tenantId, planTier, evalLimit, subjectLimit, burstLimit);
        publishEvent(BillingEventTypes.SUBSCRIPTION_CREATED, tenantId, payload);
    }

    @Override
    public void changeSubscriptionPlan(String tenantId, String newPlanTier,
                                       int evalLimit, int subjectLimit, int burstLimit) {
        var aggregate = getAggregate(tenantId);
        String oldPlanTier = aggregate != null && aggregate.subscription() != null
            ? aggregate.subscription().planTier() : null;
        var payload = new SubscriptionPlanChangedEvent(
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

    // -- Internal helpers -----------------------------------------------------

    private BillingProfileAggregate getAggregate(String tenantId) {
        UUID aggregateId = BillingProfileAggregate.deriveId(tenantId);
        AggregateKey key = new AggregateKey(
            aggregateId, BillingProfileAggregate.AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);
        return eventService.getAggregate(key, BillingProfileAggregate.class);
    }

    private void publishEvent(String eventType, String tenantId, DomainEvent payload) {
        UUID aggregateId = BillingProfileAggregate.deriveId(tenantId);
        AggregateKey key = new AggregateKey(
            aggregateId, BillingProfileAggregate.AGGREGATE_TYPE, PlatformConstants.PLATFORM_TENANT_ID);
        eventService.publishEvent(
            PublishDomainEventInput.of(PlatformConstants.PLATFORM_TENANT_ID, eventType, key, payload));
    }

}
