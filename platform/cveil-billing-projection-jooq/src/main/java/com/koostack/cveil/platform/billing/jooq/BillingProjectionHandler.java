package com.koostack.cveil.platform.billing.jooq;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koostack.cveil.core.platform.billing.BillingEventTypes;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform stream event handler that materializes billing domain events into the {@code
 * platform_billing.billing_profile} and {@code platform_billing.subscription} read models.
 *
 * <p>All operations are idempotent upserts so that replaying the event stream produces the same
 * result.
 */
public class BillingProjectionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(BillingProjectionHandler.class);

    // -- billing_profile table ------------------------------------------------
    private static final org.jooq.Table<?> BILLING_PROFILE =
            table(name("platform_billing", "billing_profile"));
    private static final org.jooq.Field<String> BP_TENANT_ID =
            field(name("tenant_id"), String.class);
    private static final org.jooq.Field<String> BP_BILLING_EMAIL =
            field(name("billing_email"), String.class);
    private static final org.jooq.Field<String> BP_TAX_ID = field(name("tax_id"), String.class);
    private static final org.jooq.Field<String> BP_PAYMENT_PROCESSOR =
            field(name("payment_processor"), String.class);
    private static final org.jooq.Field<String> BP_EXTERNAL_CUSTOMER_ID =
            field(name("external_customer_id"), String.class);
    private static final org.jooq.Field<String> BP_PAYMENT_METHOD_STATUS =
            field(name("payment_method_status"), String.class);
    private static final org.jooq.Field<OffsetDateTime> BP_CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> BP_UPDATED_AT =
            field(name("updated_at"), OffsetDateTime.class);

    // -- subscription table ---------------------------------------------------
    private static final org.jooq.Table<?> SUBSCRIPTION =
            table(name("platform_billing", "subscription"));
    private static final org.jooq.Field<String> SUB_TENANT_ID =
            field(name("tenant_id"), String.class);
    private static final org.jooq.Field<String> SUB_PLAN_TIER =
            field(name("plan_tier"), String.class);
    private static final org.jooq.Field<Long> SUB_EVAL_LIMIT =
            field(name("eval_limit_monthly"), Long.class);
    private static final org.jooq.Field<Long> SUB_SUBJECT_LIMIT =
            field(name("subject_limit"), Long.class);
    private static final org.jooq.Field<Integer> SUB_BURST_LIMIT =
            field(name("burst_limit_per_min"), Integer.class);
    private static final org.jooq.Field<String> SUB_STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<OffsetDateTime> SUB_PERIOD_START =
            field(name("current_period_start"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> SUB_PERIOD_END =
            field(name("current_period_end"), OffsetDateTime.class);
    private static final org.jooq.Field<Boolean> SUB_CANCEL_AT_PERIOD_END =
            field(name("cancel_at_period_end"), Boolean.class);
    private static final org.jooq.Field<OffsetDateTime> SUB_CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> SUB_UPDATED_AT =
            field(name("updated_at"), OffsetDateTime.class);

    private final DSLContext dsl;
    private final ObjectMapper mapper;

    public BillingProjectionHandler(DSLContext dsl) {
        this.dsl = dsl;
        this.mapper = new ObjectMapper();
    }

    /**
     * Handle a platform stream event. Compatible with the {@code StreamEventHandler.onEvent}
     * signature.
     */
    public void onEvent(String streamKey, String messageId, Map<String, String> fields) {
        String eventType = fields.get("eventType");
        String payload = fields.get("payload");
        if (eventType == null || payload == null) {
            LOG.warn("Skipping event with missing eventType or payload: messageId={}", messageId);
            return;
        }

        try {
            switch (eventType) {
                case BillingEventTypes.BILLING_PROFILE_CREATED -> handleProfileCreated(payload);
                case BillingEventTypes.BILLING_EMAIL_UPDATED -> handleEmailUpdated(payload);
                case BillingEventTypes.BILLING_TAX_ID_UPDATED -> handleTaxIdUpdated(payload);
                case BillingEventTypes.PAYMENT_PROCESSOR_SET -> handleProcessorSet(payload);
                case BillingEventTypes
                        .PAYMENT_METHOD_STATUS_UPDATED -> handlePaymentMethodStatusUpdated(payload);
                case BillingEventTypes.SUBSCRIPTION_CREATED -> handleSubscriptionCreated(payload);
                case BillingEventTypes.SUBSCRIPTION_PLAN_CHANGED -> handleSubscriptionPlanChanged(
                        payload);
                case BillingEventTypes.SUBSCRIPTION_CANCELLED -> handleSubscriptionCancelled(
                        payload);
                case BillingEventTypes.SUBSCRIPTION_RENEWED -> handleSubscriptionRenewed(payload);
                default -> LOG.trace("Ignoring unhandled event type: {}", eventType);
            }
        } catch (Exception e) {
            LOG.error("Failed to project event type={} messageId={}", eventType, messageId, e);
            throw new RuntimeException("Projection failed for " + eventType, e);
        }
    }

    // -- Billing profile handlers ---------------------------------------------

    private void handleProfileCreated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String billingEmail = node.get("billingEmail").asText();
        OffsetDateTime now = OffsetDateTime.now();

        dsl.insertInto(BILLING_PROFILE)
                .set(BP_TENANT_ID, tenantId)
                .set(BP_BILLING_EMAIL, billingEmail)
                .set(BP_CREATED_AT, now)
                .set(BP_UPDATED_AT, now)
                .onConflict(BP_TENANT_ID)
                .doUpdate()
                .set(BP_BILLING_EMAIL, billingEmail)
                .set(BP_UPDATED_AT, now)
                .execute();
    }

    private void handleEmailUpdated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String newEmail = node.get("newEmail").asText();

        dsl.update(BILLING_PROFILE)
                .set(BP_BILLING_EMAIL, newEmail)
                .set(BP_UPDATED_AT, OffsetDateTime.now())
                .where(BP_TENANT_ID.eq(tenantId))
                .execute();
    }

    private void handleTaxIdUpdated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String taxId = node.get("taxId").asText();

        dsl.update(BILLING_PROFILE)
                .set(BP_TAX_ID, taxId)
                .set(BP_UPDATED_AT, OffsetDateTime.now())
                .where(BP_TENANT_ID.eq(tenantId))
                .execute();
    }

    private void handleProcessorSet(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String processor = node.get("processor").asText();
        String customerId = node.get("customerId").asText();

        dsl.update(BILLING_PROFILE)
                .set(BP_PAYMENT_PROCESSOR, processor)
                .set(BP_EXTERNAL_CUSTOMER_ID, customerId)
                .set(BP_UPDATED_AT, OffsetDateTime.now())
                .where(BP_TENANT_ID.eq(tenantId))
                .execute();
    }

    private void handlePaymentMethodStatusUpdated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String status = node.get("status").asText();

        dsl.update(BILLING_PROFILE)
                .set(BP_PAYMENT_METHOD_STATUS, status)
                .set(BP_UPDATED_AT, OffsetDateTime.now())
                .where(BP_TENANT_ID.eq(tenantId))
                .execute();
    }

    // -- Subscription handlers ------------------------------------------------

    private void handleSubscriptionCreated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String planTier = node.get("planTier").asText();
        long evalLimit = node.get("evalLimit").asLong();
        long subjectLimit = node.get("subjectLimit").asLong();
        int burstLimit = node.get("burstLimit").asInt();
        OffsetDateTime now = OffsetDateTime.now();

        dsl.insertInto(SUBSCRIPTION)
                .set(SUB_TENANT_ID, tenantId)
                .set(SUB_PLAN_TIER, planTier)
                .set(SUB_EVAL_LIMIT, evalLimit)
                .set(SUB_SUBJECT_LIMIT, subjectLimit)
                .set(SUB_BURST_LIMIT, burstLimit)
                .set(SUB_STATUS, "active")
                .set(SUB_PERIOD_START, now)
                .set(SUB_PERIOD_END, now.plusMonths(1))
                .set(SUB_CANCEL_AT_PERIOD_END, false)
                .set(SUB_CREATED_AT, now)
                .set(SUB_UPDATED_AT, now)
                .onConflict(SUB_TENANT_ID)
                .doUpdate()
                .set(SUB_PLAN_TIER, planTier)
                .set(SUB_EVAL_LIMIT, evalLimit)
                .set(SUB_SUBJECT_LIMIT, subjectLimit)
                .set(SUB_BURST_LIMIT, burstLimit)
                .set(SUB_STATUS, "active")
                .set(SUB_UPDATED_AT, now)
                .execute();
    }

    private void handleSubscriptionPlanChanged(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String newPlanTier = node.get("newPlanTier").asText();
        long evalLimit = node.get("evalLimit").asLong();
        long subjectLimit = node.get("subjectLimit").asLong();
        int burstLimit = node.get("burstLimit").asInt();

        dsl.update(SUBSCRIPTION)
                .set(SUB_PLAN_TIER, newPlanTier)
                .set(SUB_EVAL_LIMIT, evalLimit)
                .set(SUB_SUBJECT_LIMIT, subjectLimit)
                .set(SUB_BURST_LIMIT, burstLimit)
                .set(SUB_UPDATED_AT, OffsetDateTime.now())
                .where(SUB_TENANT_ID.eq(tenantId))
                .execute();
    }

    private void handleSubscriptionCancelled(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();

        dsl.update(SUBSCRIPTION)
                .set(SUB_STATUS, "canceled")
                .set(SUB_CANCEL_AT_PERIOD_END, true)
                .set(SUB_UPDATED_AT, OffsetDateTime.now())
                .where(SUB_TENANT_ID.eq(tenantId))
                .execute();
    }

    private void handleSubscriptionRenewed(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        OffsetDateTime periodStart = parseInstant(node.get("periodStart"));
        OffsetDateTime periodEnd = parseInstant(node.get("periodEnd"));

        dsl.update(SUBSCRIPTION)
                .set(SUB_STATUS, "active")
                .set(SUB_PERIOD_START, periodStart)
                .set(SUB_PERIOD_END, periodEnd)
                .set(SUB_CANCEL_AT_PERIOD_END, false)
                .set(SUB_UPDATED_AT, OffsetDateTime.now())
                .where(SUB_TENANT_ID.eq(tenantId))
                .execute();
    }

    /**
     * Parse an Instant from a JsonNode, handling both ISO 8601 string format and numeric timestamp
     * format (Jackson JavaTimeModule default).
     */
    private static OffsetDateTime parseInstant(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            return Instant.parse(node.asText()).atOffset(ZoneOffset.UTC);
        }
        // Numeric timestamp (seconds since epoch with decimal nanoseconds)
        java.math.BigDecimal seconds = node.decimalValue();
        long epochSecond = seconds.longValue();
        int nano =
                seconds.subtract(java.math.BigDecimal.valueOf(epochSecond))
                        .multiply(java.math.BigDecimal.valueOf(1_000_000_000))
                        .intValue();
        return Instant.ofEpochSecond(epochSecond, nano).atOffset(ZoneOffset.UTC);
    }
}
