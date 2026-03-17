package com.koostack.cveil.platform.billing.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.platform.billing.BillingQueryService;
import com.koostack.cveil.core.platform.billing.models.BillingProfileInfo;
import com.koostack.cveil.core.platform.billing.models.InvoiceInfo;
import com.koostack.cveil.core.platform.billing.models.InvoiceLineItemInfo;
import com.koostack.cveil.core.platform.billing.models.PaymentMethodInfo;
import com.koostack.cveil.core.platform.billing.models.SubscriptionInfo;
import com.koostack.cveil.core.query.RetryableQuery;
import com.koostack.cveil.core.query.RetryableQuery.RetryConfig;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqBillingQueryService implements BillingQueryService<JooqBillingQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqBillingQueryService.class);

    // -- billing_profile table ------------------------------------------------
    private static final org.jooq.Table<?> BILLING_PROFILE =
            table(name("cveilbilling", "billing_profile"));
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
            table(name("cveilbilling", "subscription"));
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
    private static final org.jooq.Field<String> SUB_EXTERNAL_SUBSCRIPTION_ID =
            field(name("external_subscription_id"), String.class);
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

    // -- payment_method table -------------------------------------------------
    private static final org.jooq.Table<?> PAYMENT_METHOD =
            table(name("cveilbilling", "payment_method"));
    private static final org.jooq.Field<UUID> PM_ID = field(name("id"), UUID.class);
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
    private static final org.jooq.Table<?> INVOICE = table(name("cveilbilling", "invoice"));
    private static final org.jooq.Field<UUID> INV_ID = field(name("id"), UUID.class);
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

    // -- stripe_event_log table -----------------------------------------------
    private static final org.jooq.Table<?> STRIPE_EVENT_LOG =
            table(name("cveilbilling", "stripe_event_log"));
    private static final org.jooq.Field<String> SEL_EVENT_ID =
            field(name("stripe_event_id"), String.class);
    private static final org.jooq.Field<String> SEL_EVENT_TYPE =
            field(name("event_type"), String.class);
    private static final org.jooq.Field<OffsetDateTime> SEL_PROCESSED_AT =
            field(name("processed_at"), OffsetDateTime.class);
    private static final org.jooq.Field<Object> SEL_PAYLOAD = field(name("payload"), Object.class);

    // -- invoice_line_item table ----------------------------------------------
    private static final org.jooq.Table<?> INVOICE_LINE_ITEM =
            table(name("cveilbilling", "invoice_line_item"));
    private static final org.jooq.Field<UUID> ILI_ID = field(name("id"), UUID.class);
    private static final org.jooq.Field<UUID> ILI_INVOICE_ID =
            field(name("invoice_id"), UUID.class);
    private static final org.jooq.Field<String> ILI_STRIPE_LINE_ITEM_ID =
            field(name("stripe_line_item_id"), String.class);
    private static final org.jooq.Field<String> ILI_DESCRIPTION =
            field(name("description"), String.class);
    private static final org.jooq.Field<Long> ILI_AMOUNT = field(name("amount"), Long.class);
    private static final org.jooq.Field<String> ILI_CURRENCY =
            field(name("currency"), String.class);
    private static final org.jooq.Field<Integer> ILI_QUANTITY =
            field(name("quantity"), Integer.class);
    private static final org.jooq.Field<Long> ILI_UNIT_AMOUNT =
            field(name("unit_amount"), Long.class);
    private static final org.jooq.Field<OffsetDateTime> ILI_PERIOD_START =
            field(name("period_start"), OffsetDateTime.class);
    private static final org.jooq.Field<OffsetDateTime> ILI_PERIOD_END =
            field(name("period_end"), OffsetDateTime.class);
    private static final org.jooq.Field<Boolean> ILI_PRORATION =
            field(name("proration"), Boolean.class);
    private static final org.jooq.Field<OffsetDateTime> ILI_CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);

    private DSLContext dsl;
    private RetryConfig retryConfig = RetryableQuery.DEFAULT;

    @Override
    public String getName() {
        return "jooq-billing-query";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqBillingQueryConfig> getConfigurationType() {
        return JooqBillingQueryConfig.class;
    }

    @Override
    public void configure(JooqBillingQueryConfig config) {
        this.dsl = config.dsl();
        LOG.info("JooqBillingQueryService configured");
    }

    @Override
    public boolean isReady() {
        return dsl != null;
    }

    public void setRetryConfig(RetryConfig retryConfig) {
        this.retryConfig = retryConfig;
    }

    @Override
    public BillingProfileInfo getBillingProfile(String tenantId) {
        return RetryableQuery.withRetry(
                () -> {
                    Record record =
                            dsl.select()
                                    .from(BILLING_PROFILE)
                                    .where(BP_TENANT_ID.eq(tenantId))
                                    .fetchOne();
                    return record != null ? toProfileInfo(record) : null;
                },
                result -> result == null,
                retryConfig,
                "getBillingProfile(" + tenantId + ")");
    }

    @Override
    public SubscriptionInfo getSubscription(String tenantId) {
        return RetryableQuery.withRetry(
                () -> {
                    Record record =
                            dsl.select()
                                    .from(SUBSCRIPTION)
                                    .where(SUB_TENANT_ID.eq(tenantId))
                                    .fetchOne();
                    return record != null ? toSubscriptionInfo(record) : null;
                },
                result -> result == null,
                retryConfig,
                "getSubscription(" + tenantId + ")");
    }

    @Override
    public List<BillingProfileInfo> listBillingProfiles() {
        return RetryableQuery.withRetryCollection(
                () ->
                        dsl.select()
                                .from(BILLING_PROFILE)
                                .orderBy(BP_CREATED_AT.asc())
                                .fetch()
                                .map(this::toProfileInfo),
                retryConfig,
                "listBillingProfiles");
    }

    // -- Payment method queries -----------------------------------------------

    @Override
    public List<PaymentMethodInfo> listPaymentMethods(String tenantId) {
        return RetryableQuery.withRetryCollection(
                () ->
                        dsl.select()
                                .from(PAYMENT_METHOD)
                                .where(PM_TENANT_ID.eq(tenantId))
                                .and(PM_STATUS.eq("active"))
                                .orderBy(PM_IS_DEFAULT.desc(), PM_CREATED_AT.desc())
                                .fetch()
                                .map(this::toPaymentMethodInfo),
                retryConfig,
                "listPaymentMethods(" + tenantId + ")");
    }

    @Override
    public PaymentMethodInfo getDefaultPaymentMethod(String tenantId) {
        return RetryableQuery.withRetry(
                () -> {
                    Record record =
                            dsl.select()
                                    .from(PAYMENT_METHOD)
                                    .where(PM_TENANT_ID.eq(tenantId))
                                    .and(PM_IS_DEFAULT.eq(true))
                                    .and(PM_STATUS.eq("active"))
                                    .fetchOne();
                    return record != null ? toPaymentMethodInfo(record) : null;
                },
                result -> result == null,
                retryConfig,
                "getDefaultPaymentMethod(" + tenantId + ")");
    }

    // -- Invoice queries ------------------------------------------------------

    @Override
    public List<InvoiceInfo> listInvoices(String tenantId, int page, int pageSize) {
        return RetryableQuery.withRetryCollection(
                () ->
                        dsl.select()
                                .from(INVOICE)
                                .where(INV_TENANT_ID.eq(tenantId))
                                .orderBy(INV_CREATED_AT.desc())
                                .limit(pageSize)
                                .offset(page * pageSize)
                                .fetch()
                                .map(this::toInvoiceInfo),
                retryConfig,
                "listInvoices(" + tenantId + ")");
    }

    @Override
    public InvoiceInfo getInvoice(String tenantId, String invoiceId) {
        return RetryableQuery.withRetry(
                () -> {
                    Record invoiceRecord =
                            dsl.select()
                                    .from(INVOICE)
                                    .where(INV_TENANT_ID.eq(tenantId))
                                    .and(INV_ID.eq(UUID.fromString(invoiceId)))
                                    .fetchOne();
                    if (invoiceRecord == null) {
                        return null;
                    }
                    UUID invoiceUuid = invoiceRecord.get(INV_ID);
                    List<InvoiceLineItemInfo> lineItems =
                            dsl.select()
                                    .from(INVOICE_LINE_ITEM)
                                    .where(ILI_INVOICE_ID.eq(invoiceUuid))
                                    .orderBy(ILI_CREATED_AT.asc())
                                    .fetch()
                                    .map(this::toLineItemInfo);
                    return toInvoiceInfo(invoiceRecord, lineItems);
                },
                result -> result == null,
                retryConfig,
                "getInvoice(" + tenantId + ", " + invoiceId + ")");
    }

    private BillingProfileInfo toProfileInfo(Record r) {
        return new BillingProfileInfo(
                r.get(BP_TENANT_ID),
                r.get(BP_BILLING_EMAIL),
                r.get(BP_TAX_ID),
                r.get(BP_PAYMENT_PROCESSOR),
                r.get(BP_EXTERNAL_CUSTOMER_ID),
                r.get(BP_PAYMENT_METHOD_STATUS),
                toInstant(r.get(BP_CREATED_AT)),
                toInstant(r.get(BP_UPDATED_AT)));
    }

    private SubscriptionInfo toSubscriptionInfo(Record r) {
        return new SubscriptionInfo(
                r.get(SUB_TENANT_ID),
                r.get(SUB_PLAN_TIER),
                safeInt(r.get(SUB_EVAL_LIMIT)),
                safeInt(r.get(SUB_SUBJECT_LIMIT)),
                r.get(SUB_BURST_LIMIT) != null ? r.get(SUB_BURST_LIMIT) : 0,
                r.get(SUB_STATUS),
                r.get(SUB_EXTERNAL_SUBSCRIPTION_ID),
                toInstant(r.get(SUB_PERIOD_START)),
                toInstant(r.get(SUB_PERIOD_END)),
                Boolean.TRUE.equals(r.get(SUB_CANCEL_AT_PERIOD_END)),
                toInstant(r.get(SUB_CREATED_AT)),
                toInstant(r.get(SUB_UPDATED_AT)));
    }

    private PaymentMethodInfo toPaymentMethodInfo(Record r) {
        return new PaymentMethodInfo(
                safeUuid(r.get(PM_ID)),
                r.get(PM_TENANT_ID),
                r.get(PM_STRIPE_PM_ID),
                r.get(PM_TYPE),
                r.get(PM_CARD_BRAND),
                r.get(PM_CARD_LAST4),
                safeShort(r.get(PM_CARD_EXP_MONTH)),
                safeShort(r.get(PM_CARD_EXP_YEAR)),
                Boolean.TRUE.equals(r.get(PM_IS_DEFAULT)),
                r.get(PM_STATUS),
                toInstant(r.get(PM_CREATED_AT)),
                toInstant(r.get(PM_UPDATED_AT)));
    }

    private InvoiceInfo toInvoiceInfo(Record r) {
        return toInvoiceInfo(r, List.of());
    }

    private InvoiceInfo toInvoiceInfo(Record r, List<InvoiceLineItemInfo> lineItems) {
        return new InvoiceInfo(
                safeUuid(r.get(INV_ID)),
                r.get(INV_TENANT_ID),
                r.get(INV_STRIPE_INVOICE_ID),
                r.get(INV_STRIPE_SUBSCRIPTION_ID),
                r.get(INV_STATUS),
                r.get(INV_CURRENCY),
                safeLong(r.get(INV_AMOUNT_DUE)),
                safeLong(r.get(INV_AMOUNT_PAID)),
                safeLong(r.get(INV_AMOUNT_REMAINING)),
                safeLong(r.get(INV_SUBTOTAL)),
                safeLong(r.get(INV_TAX)),
                safeLong(r.get(INV_TOTAL)),
                r.get(INV_HOSTED_URL),
                r.get(INV_PDF_URL),
                toInstant(r.get(INV_PERIOD_START)),
                toInstant(r.get(INV_PERIOD_END)),
                toInstant(r.get(INV_DUE_DATE)),
                toInstant(r.get(INV_PAID_AT)),
                toInstant(r.get(INV_CREATED_AT)),
                toInstant(r.get(INV_UPDATED_AT)),
                lineItems);
    }

    private InvoiceLineItemInfo toLineItemInfo(Record r) {
        return new InvoiceLineItemInfo(
                safeUuid(r.get(ILI_ID)),
                safeUuid(r.get(ILI_INVOICE_ID)),
                r.get(ILI_STRIPE_LINE_ITEM_ID),
                r.get(ILI_DESCRIPTION),
                safeLong(r.get(ILI_AMOUNT)),
                r.get(ILI_CURRENCY),
                r.get(ILI_QUANTITY) != null ? r.get(ILI_QUANTITY) : 0,
                safeLong(r.get(ILI_UNIT_AMOUNT)),
                toInstant(r.get(ILI_PERIOD_START)),
                toInstant(r.get(ILI_PERIOD_END)),
                Boolean.TRUE.equals(r.get(ILI_PRORATION)),
                toInstant(r.get(ILI_CREATED_AT)));
    }

    private static int safeInt(Long value) {
        return value != null ? value.intValue() : 0;
    }

    private static long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private static int safeShort(Short value) {
        return value != null ? value.intValue() : 0;
    }

    private static String safeUuid(UUID value) {
        return value != null ? value.toString() : null;
    }

    // -- Webhook idempotency --------------------------------------------------

    @Override
    public boolean isWebhookEventProcessed(String externalEventId) {
        return RetryableQuery.withRetry(
                () ->
                        dsl.fetchExists(
                                dsl.selectFrom(STRIPE_EVENT_LOG)
                                        .where(SEL_EVENT_ID.eq(externalEventId))),
                result -> false,
                retryConfig,
                "isWebhookEventProcessed(" + externalEventId + ")");
    }

    @Override
    public void markWebhookEventProcessed(
            String externalEventId, String eventType, String payload) {
        dsl.insertInto(STRIPE_EVENT_LOG)
                .set(SEL_EVENT_ID, externalEventId)
                .set(SEL_EVENT_TYPE, eventType)
                .set(SEL_PROCESSED_AT, OffsetDateTime.now())
                .set(
                        SEL_PAYLOAD,
                        org.jooq.impl.DSL.val(payload).cast(org.jooq.impl.SQLDataType.JSONB))
                .onConflict(SEL_EVENT_ID)
                .doNothing()
                .execute();
    }

    // -- Lookup by external customer ID ---------------------------------------

    @Override
    public BillingProfileInfo getBillingProfileByExternalCustomerId(String externalCustomerId) {
        return RetryableQuery.withRetry(
                () -> {
                    Record record =
                            dsl.select()
                                    .from(BILLING_PROFILE)
                                    .where(BP_EXTERNAL_CUSTOMER_ID.eq(externalCustomerId))
                                    .fetchOne();
                    return record != null ? toProfileInfo(record) : null;
                },
                result -> result == null,
                retryConfig,
                "getBillingProfileByExternalCustomerId(" + externalCustomerId + ")");
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }
}
