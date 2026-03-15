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

    // -- payment_method table -------------------------------------------------
    private static final org.jooq.Table<?> PAYMENT_METHOD =
            table(name("platform_billing", "payment_method"));
    private static final org.jooq.Field<java.util.UUID> PM_ID =
            field(name("id"), java.util.UUID.class);
    private static final org.jooq.Field<String> PM_TENANT_ID =
            field(name("tenant_id"), String.class);
    private static final org.jooq.Field<String> PM_STRIPE_PM_ID =
            field(name("stripe_payment_method_id"), String.class);
    private static final org.jooq.Field<String> PM_TYPE = field(name("type"), String.class);
    private static final org.jooq.Field<String> PM_CARD_BRAND =
            field(name("card_brand"), String.class);
    private static final org.jooq.Field<String> PM_CARD_LAST4 =
            field(name("card_last4"), String.class);
    private static final org.jooq.Field<Short> PM_CARD_EXP_MONTH =
            field(name("card_exp_month"), Short.class);
    private static final org.jooq.Field<Short> PM_CARD_EXP_YEAR =
            field(name("card_exp_year"), Short.class);
    private static final org.jooq.Field<Boolean> PM_IS_DEFAULT =
            field(name("is_default"), Boolean.class);
    private static final org.jooq.Field<String> PM_STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<OffsetDateTime> PM_CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> PM_UPDATED_AT =
            field(name("updated_at"), OffsetDateTime.class);

    // -- invoice table --------------------------------------------------------
    private static final org.jooq.Table<?> INVOICE = table(name("platform_billing", "invoice"));
    private static final org.jooq.Field<String> INV_TENANT_ID =
            field(name("tenant_id"), String.class);
    private static final org.jooq.Field<String> INV_STRIPE_INVOICE_ID =
            field(name("stripe_invoice_id"), String.class);
    private static final org.jooq.Field<String> INV_STRIPE_SUBSCRIPTION_ID =
            field(name("stripe_subscription_id"), String.class);
    private static final org.jooq.Field<String> INV_STATUS = field(name("status"), String.class);
    private static final org.jooq.Field<String> INV_CURRENCY =
            field(name("currency"), String.class);
    private static final org.jooq.Field<Long> INV_AMOUNT_DUE =
            field(name("amount_due"), Long.class);
    private static final org.jooq.Field<Long> INV_AMOUNT_PAID =
            field(name("amount_paid"), Long.class);
    private static final org.jooq.Field<Long> INV_AMOUNT_REMAINING =
            field(name("amount_remaining"), Long.class);
    private static final org.jooq.Field<Long> INV_SUBTOTAL = field(name("subtotal"), Long.class);
    private static final org.jooq.Field<Long> INV_TAX = field(name("tax"), Long.class);
    private static final org.jooq.Field<Long> INV_TOTAL = field(name("total"), Long.class);
    private static final org.jooq.Field<String> INV_HOSTED_URL =
            field(name("hosted_invoice_url"), String.class);
    private static final org.jooq.Field<String> INV_PDF_URL =
            field(name("invoice_pdf_url"), String.class);
    private static final org.jooq.Field<OffsetDateTime> INV_PERIOD_START =
            field(name("period_start"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> INV_PERIOD_END =
            field(name("period_end"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> INV_DUE_DATE =
            field(name("due_date"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> INV_PAID_AT =
            field(name("paid_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> INV_CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> INV_UPDATED_AT =
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
    private static final org.jooq.Field<String> SUB_EXTERNAL_SUBSCRIPTION_ID =
            field(name("external_subscription_id"), String.class);

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
                case BillingEventTypes.PAYMENT_METHOD_ATTACHED -> handlePaymentMethodAttached(
                        payload);
                case BillingEventTypes.PAYMENT_METHOD_DETACHED -> handlePaymentMethodDetached(
                        payload);
                case BillingEventTypes
                        .PAYMENT_METHOD_DEFAULT_CHANGED -> handlePaymentMethodDefaultChanged(
                        payload);
                case BillingEventTypes.INVOICE_CREATED -> handleInvoiceCreated(payload);
                case BillingEventTypes.INVOICE_UPDATED -> handleInvoiceUpdated(payload);
                case BillingEventTypes.INVOICE_PAID -> handleInvoicePaid(payload);
                case BillingEventTypes.USAGE_REPORTED -> handleUsageReported(payload);
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
        String externalSubId =
                node.has("externalSubscriptionId")
                        ? node.get("externalSubscriptionId").asText(null)
                        : null;
        OffsetDateTime periodStart =
                node.has("periodStart") ? parseInstant(node.get("periodStart")) : null;
        OffsetDateTime periodEnd =
                node.has("periodEnd") ? parseInstant(node.get("periodEnd")) : null;
        OffsetDateTime now = OffsetDateTime.now();
        if (periodStart == null) periodStart = now;
        if (periodEnd == null) periodEnd = now.plusMonths(1);

        dsl.insertInto(SUBSCRIPTION)
                .set(SUB_TENANT_ID, tenantId)
                .set(SUB_PLAN_TIER, planTier)
                .set(SUB_EVAL_LIMIT, evalLimit)
                .set(SUB_SUBJECT_LIMIT, subjectLimit)
                .set(SUB_BURST_LIMIT, burstLimit)
                .set(SUB_STATUS, "active")
                .set(SUB_PERIOD_START, periodStart)
                .set(SUB_PERIOD_END, periodEnd)
                .set(SUB_CANCEL_AT_PERIOD_END, false)
                .set(SUB_EXTERNAL_SUBSCRIPTION_ID, externalSubId)
                .set(SUB_CREATED_AT, now)
                .set(SUB_UPDATED_AT, now)
                .onConflict(SUB_TENANT_ID)
                .doUpdate()
                .set(SUB_PLAN_TIER, planTier)
                .set(SUB_EVAL_LIMIT, evalLimit)
                .set(SUB_SUBJECT_LIMIT, subjectLimit)
                .set(SUB_BURST_LIMIT, burstLimit)
                .set(SUB_STATUS, "active")
                .set(SUB_PERIOD_START, periodStart)
                .set(SUB_PERIOD_END, periodEnd)
                .set(SUB_EXTERNAL_SUBSCRIPTION_ID, externalSubId)
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

    // -- Payment method handlers ----------------------------------------------

    private void handlePaymentMethodAttached(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String externalId = node.get("externalPaymentMethodId").asText();
        String type = node.get("type").asText();
        String brand = node.has("brand") ? node.get("brand").asText(null) : null;
        String last4 = node.has("last4") ? node.get("last4").asText(null) : null;
        short expMonth = (short) node.get("expMonth").asInt();
        short expYear = (short) node.get("expYear").asInt();
        boolean isDefault = node.get("isDefault").asBoolean();
        OffsetDateTime now = OffsetDateTime.now();

        // If this is the default, clear other defaults for the tenant first
        if (isDefault) {
            dsl.update(PAYMENT_METHOD)
                    .set(PM_IS_DEFAULT, false)
                    .set(PM_UPDATED_AT, now)
                    .where(PM_TENANT_ID.eq(tenantId))
                    .and(PM_IS_DEFAULT.eq(true))
                    .execute();
        }

        dsl.insertInto(PAYMENT_METHOD)
                .set(PM_TENANT_ID, tenantId)
                .set(PM_STRIPE_PM_ID, externalId)
                .set(PM_TYPE, type)
                .set(PM_CARD_BRAND, brand)
                .set(PM_CARD_LAST4, last4)
                .set(PM_CARD_EXP_MONTH, expMonth)
                .set(PM_CARD_EXP_YEAR, expYear)
                .set(PM_IS_DEFAULT, isDefault)
                .set(PM_STATUS, "active")
                .set(PM_CREATED_AT, now)
                .set(PM_UPDATED_AT, now)
                .onConflict(PM_STRIPE_PM_ID)
                .doUpdate()
                .set(PM_TYPE, type)
                .set(PM_CARD_BRAND, brand)
                .set(PM_CARD_LAST4, last4)
                .set(PM_CARD_EXP_MONTH, expMonth)
                .set(PM_CARD_EXP_YEAR, expYear)
                .set(PM_IS_DEFAULT, isDefault)
                .set(PM_STATUS, "active")
                .set(PM_UPDATED_AT, now)
                .execute();

        // Update billing_profile payment_method_status to 'valid'
        dsl.update(BILLING_PROFILE)
                .set(BP_PAYMENT_METHOD_STATUS, "valid")
                .set(BP_UPDATED_AT, now)
                .where(BP_TENANT_ID.eq(tenantId))
                .execute();
    }

    private void handlePaymentMethodDetached(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String externalId = node.get("externalPaymentMethodId").asText();

        // Read tenant_id before marking as detached
        String tenantId = node.has("tenantId") ? node.get("tenantId").asText(null) : null;
        if (tenantId == null) {
            var record =
                    dsl.select(PM_TENANT_ID)
                            .from(PAYMENT_METHOD)
                            .where(PM_STRIPE_PM_ID.eq(externalId))
                            .fetchOne();
            tenantId = record != null ? record.get(PM_TENANT_ID) : null;
        }

        dsl.update(PAYMENT_METHOD)
                .set(PM_STATUS, "detached")
                .set(PM_IS_DEFAULT, false)
                .set(PM_UPDATED_AT, OffsetDateTime.now())
                .where(PM_STRIPE_PM_ID.eq(externalId))
                .execute();

        // Update billing_profile if no active PMs remain
        if (tenantId != null) {
            int activeCount =
                    dsl.selectCount()
                            .from(PAYMENT_METHOD)
                            .where(PM_TENANT_ID.eq(tenantId))
                            .and(PM_STATUS.eq("active"))
                            .fetchOne(0, int.class);

            if (activeCount == 0) {
                dsl.update(BILLING_PROFILE)
                        .set(BP_PAYMENT_METHOD_STATUS, "unconfigured")
                        .set(BP_UPDATED_AT, OffsetDateTime.now())
                        .where(BP_TENANT_ID.eq(tenantId))
                        .execute();
            }
        }
    }

    private void handlePaymentMethodDefaultChanged(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String externalId = node.get("externalPaymentMethodId").asText();
        OffsetDateTime now = OffsetDateTime.now();

        // Clear previous default for this tenant
        dsl.update(PAYMENT_METHOD)
                .set(PM_IS_DEFAULT, false)
                .set(PM_UPDATED_AT, now)
                .where(PM_TENANT_ID.eq(tenantId))
                .and(PM_IS_DEFAULT.eq(true))
                .execute();

        // Set new default
        dsl.update(PAYMENT_METHOD)
                .set(PM_IS_DEFAULT, true)
                .set(PM_UPDATED_AT, now)
                .where(PM_STRIPE_PM_ID.eq(externalId))
                .execute();
    }

    // -- Invoice handlers -----------------------------------------------------

    private void handleInvoiceCreated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String externalInvoiceId = node.get("externalInvoiceId").asText();
        String externalSubId =
                node.has("externalSubscriptionId")
                        ? node.get("externalSubscriptionId").asText(null)
                        : null;
        String status = node.get("status").asText();
        String currency = node.has("currency") ? node.get("currency").asText("usd") : "usd";
        long amountDue = node.get("amountDue").asLong();
        long subtotal = node.get("subtotal").asLong();
        long total = node.get("total").asLong();
        String hostedUrl =
                node.has("hostedInvoiceUrl") ? node.get("hostedInvoiceUrl").asText(null) : null;
        String pdfUrl = node.has("invoicePdfUrl") ? node.get("invoicePdfUrl").asText(null) : null;
        OffsetDateTime periodStart = parseInstant(node.get("periodStart"));
        OffsetDateTime periodEnd = parseInstant(node.get("periodEnd"));
        OffsetDateTime dueDate = parseInstant(node.get("dueDate"));
        OffsetDateTime now = OffsetDateTime.now();

        dsl.insertInto(INVOICE)
                .set(INV_TENANT_ID, tenantId)
                .set(INV_STRIPE_INVOICE_ID, externalInvoiceId)
                .set(INV_STRIPE_SUBSCRIPTION_ID, externalSubId)
                .set(INV_STATUS, status)
                .set(INV_CURRENCY, currency)
                .set(INV_AMOUNT_DUE, amountDue)
                .set(INV_AMOUNT_PAID, 0L)
                .set(INV_AMOUNT_REMAINING, amountDue)
                .set(INV_SUBTOTAL, subtotal)
                .set(INV_TOTAL, total)
                .set(INV_HOSTED_URL, hostedUrl)
                .set(INV_PDF_URL, pdfUrl)
                .set(INV_PERIOD_START, periodStart)
                .set(INV_PERIOD_END, periodEnd)
                .set(INV_DUE_DATE, dueDate)
                .set(INV_CREATED_AT, now)
                .set(INV_UPDATED_AT, now)
                .onConflict(INV_STRIPE_INVOICE_ID)
                .doUpdate()
                .set(INV_STATUS, status)
                .set(INV_AMOUNT_DUE, amountDue)
                .set(INV_SUBTOTAL, subtotal)
                .set(INV_TOTAL, total)
                .set(INV_HOSTED_URL, hostedUrl)
                .set(INV_PDF_URL, pdfUrl)
                .set(INV_PERIOD_START, periodStart)
                .set(INV_PERIOD_END, periodEnd)
                .set(INV_DUE_DATE, dueDate)
                .set(INV_UPDATED_AT, now)
                .execute();
    }

    private void handleInvoiceUpdated(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String externalInvoiceId = node.get("externalInvoiceId").asText();
        String status = node.get("status").asText();
        long amountDue = node.get("amountDue").asLong();
        long amountPaid = node.get("amountPaid").asLong();
        long amountRemaining = node.get("amountRemaining").asLong();
        long total = node.get("total").asLong();
        String hostedUrl =
                node.has("hostedInvoiceUrl") ? node.get("hostedInvoiceUrl").asText(null) : null;
        String pdfUrl = node.has("invoicePdfUrl") ? node.get("invoicePdfUrl").asText(null) : null;
        OffsetDateTime paidAt = parseInstant(node.get("paidAt"));

        dsl.update(INVOICE)
                .set(INV_STATUS, status)
                .set(INV_AMOUNT_DUE, amountDue)
                .set(INV_AMOUNT_PAID, amountPaid)
                .set(INV_AMOUNT_REMAINING, amountRemaining)
                .set(INV_TOTAL, total)
                .set(INV_HOSTED_URL, hostedUrl)
                .set(INV_PDF_URL, pdfUrl)
                .set(INV_PAID_AT, paidAt)
                .set(INV_UPDATED_AT, OffsetDateTime.now())
                .where(INV_STRIPE_INVOICE_ID.eq(externalInvoiceId))
                .execute();
    }

    private void handleInvoicePaid(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String externalInvoiceId = node.get("externalInvoiceId").asText();
        long amountPaid = node.get("amountPaid").asLong();
        OffsetDateTime paidAt = parseInstant(node.get("paidAt"));

        dsl.update(INVOICE)
                .set(INV_STATUS, "paid")
                .set(INV_AMOUNT_PAID, amountPaid)
                .set(INV_AMOUNT_REMAINING, 0L)
                .set(INV_PAID_AT, paidAt)
                .set(INV_UPDATED_AT, OffsetDateTime.now())
                .where(INV_STRIPE_INVOICE_ID.eq(externalInvoiceId))
                .execute();
    }

    // -- Usage handler --------------------------------------------------------

    private void handleUsageReported(String payload) throws Exception {
        var node = mapper.readTree(payload);
        String tenantId = node.get("tenantId").asText();
        String metricName = node.get("metricName").asText();
        long quantity = node.get("quantity").asLong();
        LOG.debug(
                "Usage reported: tenant={}, metric={}, quantity={}",
                tenantId,
                metricName,
                quantity);
        // Usage events are informational — no read model update needed.
        // The daily_usage_rollup table is updated by the metering subsystem directly.
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
