package com.koostack.cveil.core.platform.billing.aggregates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.PlatformConstants;
import com.koostack.cveil.core.models.AggregateRoot;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import com.koostack.cveil.core.platform.billing.events.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root for the BillingProfile domain entity.
 *
 * <p>Lives in the platform-scoped event store. Subscription state is nested within this aggregate
 * rather than modelled as a separate root.
 */
public record BillingProfileAggregate(
        String tenantId,
        UUID aggregateId,
        String billingTenantId,
        String billingEmail,
        String taxId,
        String paymentProcessor,
        String paymentProcessorCustomerId,
        String paymentMethodStatus,
        SubscriptionState subscription,
        Long version)
        implements AggregateRoot {

    public static final String AGGREGATE_TYPE = "billing-profile";

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    /** Derive a deterministic aggregate ID from a tenant's business identifier. */
    public static UUID deriveId(String billingTenantId) {
        return UUID.nameUUIDFromBytes(
                ("billing-profile:" + billingTenantId).getBytes(StandardCharsets.UTF_8));
    }

    /** Nested subscription state within the billing profile aggregate. */
    public record SubscriptionState(
            String planTier,
            int evalLimit,
            int subjectLimit,
            int burstLimit,
            String status,
            Instant periodStart,
            Instant periodEnd,
            boolean cancelAtPeriodEnd) {}

    /** Apply a domain event to the current aggregate state, returning a new immutable instance. */
    public static BillingProfileAggregate apply(
            BillingProfileAggregate current,
            String eventType,
            JsonNode payloadNode,
            ObjectMapper mapper) {
        try {
            return switch (eventType) {
                case BillingEventTypes.BILLING_PROFILE_CREATED -> {
                    var e = mapper.treeToValue(payloadNode, BillingProfileCreatedEvent.class);
                    yield new BillingProfileAggregate(
                            PlatformConstants.PLATFORM_TENANT_ID,
                            deriveId(e.tenantId()),
                            e.tenantId(),
                            e.billingEmail(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            1L);
                }
                case BillingEventTypes.BILLING_EMAIL_UPDATED -> {
                    var e = mapper.treeToValue(payloadNode, BillingEmailUpdatedEvent.class);
                    yield new BillingProfileAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.billingTenantId(),
                            e.newEmail(),
                            current.taxId(),
                            current.paymentProcessor(),
                            current.paymentProcessorCustomerId(),
                            current.paymentMethodStatus(),
                            current.subscription(),
                            current.version() + 1);
                }
                case BillingEventTypes.BILLING_TAX_ID_UPDATED -> {
                    var e = mapper.treeToValue(payloadNode, BillingTaxIdUpdatedEvent.class);
                    yield new BillingProfileAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.billingTenantId(),
                            current.billingEmail(),
                            e.taxId(),
                            current.paymentProcessor(),
                            current.paymentProcessorCustomerId(),
                            current.paymentMethodStatus(),
                            current.subscription(),
                            current.version() + 1);
                }
                case BillingEventTypes.PAYMENT_PROCESSOR_SET -> {
                    var e = mapper.treeToValue(payloadNode, PaymentProcessorSetEvent.class);
                    yield new BillingProfileAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.billingTenantId(),
                            current.billingEmail(),
                            current.taxId(),
                            e.processor(),
                            e.customerId(),
                            current.paymentMethodStatus(),
                            current.subscription(),
                            current.version() + 1);
                }
                case BillingEventTypes.PAYMENT_METHOD_STATUS_UPDATED -> {
                    var e = mapper.treeToValue(payloadNode, PaymentMethodStatusUpdatedEvent.class);
                    yield new BillingProfileAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.billingTenantId(),
                            current.billingEmail(),
                            current.taxId(),
                            current.paymentProcessor(),
                            current.paymentProcessorCustomerId(),
                            e.status(),
                            current.subscription(),
                            current.version() + 1);
                }
                case BillingEventTypes.SUBSCRIPTION_CREATED -> {
                    var e = mapper.treeToValue(payloadNode, SubscriptionCreatedEvent.class);
                    var sub =
                            new SubscriptionState(
                                    e.planTier(),
                                    e.evalLimit(),
                                    e.subjectLimit(),
                                    e.burstLimit(),
                                    "active",
                                    null,
                                    null,
                                    false);
                    yield new BillingProfileAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.billingTenantId(),
                            current.billingEmail(),
                            current.taxId(),
                            current.paymentProcessor(),
                            current.paymentProcessorCustomerId(),
                            current.paymentMethodStatus(),
                            sub,
                            current.version() + 1);
                }
                case BillingEventTypes.SUBSCRIPTION_PLAN_CHANGED -> {
                    var e = mapper.treeToValue(payloadNode, SubscriptionPlanChangedEvent.class);
                    var prev = current.subscription();
                    var sub =
                            new SubscriptionState(
                                    e.newPlanTier(),
                                    e.evalLimit(),
                                    e.subjectLimit(),
                                    e.burstLimit(),
                                    prev != null ? prev.status() : "active",
                                    prev != null ? prev.periodStart() : null,
                                    prev != null ? prev.periodEnd() : null,
                                    prev != null && prev.cancelAtPeriodEnd());
                    yield new BillingProfileAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.billingTenantId(),
                            current.billingEmail(),
                            current.taxId(),
                            current.paymentProcessor(),
                            current.paymentProcessorCustomerId(),
                            current.paymentMethodStatus(),
                            sub,
                            current.version() + 1);
                }
                case BillingEventTypes.SUBSCRIPTION_CANCELLED -> {
                    var prev = current.subscription();
                    var sub =
                            prev != null
                                    ? new SubscriptionState(
                                            prev.planTier(),
                                            prev.evalLimit(),
                                            prev.subjectLimit(),
                                            prev.burstLimit(),
                                            "canceled",
                                            prev.periodStart(),
                                            prev.periodEnd(),
                                            true)
                                    : null;
                    yield new BillingProfileAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.billingTenantId(),
                            current.billingEmail(),
                            current.taxId(),
                            current.paymentProcessor(),
                            current.paymentProcessorCustomerId(),
                            current.paymentMethodStatus(),
                            sub,
                            current.version() + 1);
                }
                case BillingEventTypes.SUBSCRIPTION_RENEWED -> {
                    var e = mapper.treeToValue(payloadNode, SubscriptionRenewedEvent.class);
                    var prev = current.subscription();
                    var sub =
                            prev != null
                                    ? new SubscriptionState(
                                            prev.planTier(),
                                            prev.evalLimit(),
                                            prev.subjectLimit(),
                                            prev.burstLimit(),
                                            "active",
                                            e.periodStart(),
                                            e.periodEnd(),
                                            false)
                                    : null;
                    yield new BillingProfileAggregate(
                            current.tenantId(),
                            current.aggregateId(),
                            current.billingTenantId(),
                            current.billingEmail(),
                            current.taxId(),
                            current.paymentProcessor(),
                            current.paymentProcessorCustomerId(),
                            current.paymentMethodStatus(),
                            sub,
                            current.version() + 1);
                }
                default -> current;
            };
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply event " + eventType, e);
        }
    }
}
