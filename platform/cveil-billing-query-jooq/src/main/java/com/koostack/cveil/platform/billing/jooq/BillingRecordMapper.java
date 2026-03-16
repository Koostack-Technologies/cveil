package com.koostack.cveil.platform.billing.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.platform.billing.models.BillingProfileInfo;
import com.koostack.cveil.core.platform.billing.models.InvoiceInfo;
import com.koostack.cveil.core.platform.billing.models.InvoiceLineItemInfo;
import com.koostack.cveil.core.platform.billing.models.PaymentMethodInfo;
import com.koostack.cveil.core.platform.billing.models.SubscriptionInfo;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.Record;

/** Shared jOOQ field constants and record-to-domain mapping utilities for billing queries. */
public final class BillingRecordMapper {

    // -- billing_profile table ------------------------------------------------
    static final org.jooq.Table<?> BILLING_PROFILE = table(name("cveilbilling", "billing_profile"));
    static final org.jooq.Field<String> BP_TENANT_ID = field(name("tenant_id"), String.class);
    static final org.jooq.Field<String> BP_BILLING_EMAIL =
            field(name("billing_email"), String.class);
    static final org.jooq.Field<String> BP_TAX_ID = field(name("tax_id"), String.class);
    static final org.jooq.Field<String> BP_PAYMENT_PROCESSOR =
            field(name("payment_processor"), String.class);
    static final org.jooq.Field<String> BP_EXTERNAL_CUSTOMER_ID =
            field(name("external_customer_id"), String.class);
    static final org.jooq.Field<String> BP_PAYMENT_METHOD_STATUS =
            field(name("payment_method_status"), String.class);
    static final org.jooq.Field<OffsetDateTime> BP_CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    static final org.jooq.Field<OffsetDateTime> BP_UPDATED_AT =
            field(name("updated_at"), OffsetDateTime.class);

    // -- subscription table ---------------------------------------------------
    static final org.jooq.Table<?> SUBSCRIPTION = table(name("cveilbilling", "subscription"));
    static final org.jooq.Field<String> SUB_TENANT_ID = field(name("tenant_id"), String.class);
    static final org.jooq.Field<String> SUB_PLAN_TIER = field(name("plan_tier"), String.class);
    static final org.jooq.Field<Long> SUB_EVAL_LIMIT =
            field(name("eval_limit_monthly"), Long.class);
    static final org.jooq.Field<Long> SUB_SUBJECT_LIMIT = field(name("subject_limit"), Long.class);
    static final org.jooq.Field<Integer> SUB_BURST_LIMIT =
            field(name("burst_limit_per_min"), Integer.class);
    static final org.jooq.Field<String> SUB_STATUS = field(name("status"), String.class);
    static final org.jooq.Field<String> SUB_EXTERNAL_SUBSCRIPTION_ID =
            field(name("external_subscription_id"), String.class);
    static final org.jooq.Field<OffsetDateTime> SUB_PERIOD_START =
            field(name("current_period_start"), OffsetDateTime.class);
    static final org.jooq.Field<OffsetDateTime> SUB_PERIOD_END =
            field(name("current_period_end"), OffsetDateTime.class);
    static final org.jooq.Field<Boolean> SUB_CANCEL_AT_PERIOD_END =
            field(name("cancel_at_period_end"), Boolean.class);
    static final org.jooq.Field<OffsetDateTime> SUB_CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    static final org.jooq.Field<OffsetDateTime> SUB_UPDATED_AT =
            field(name("updated_at"), OffsetDateTime.class);

    // -- payment_method table -------------------------------------------------
    static final org.jooq.Table<?> PAYMENT_METHOD = table(name("cveilbilling", "payment_method"));
    static final org.jooq.Field<UUID> PM_ID = field(name("id"), UUID.class);
    static final org.jooq.Field<String> PM_TENANT_ID = field(name("tenant_id"), String.class);
    static final org.jooq.Field<String> PM_STRIPE_PM_ID =
            field(name("stripe_payment_method_id"), String.class);
    static final org.jooq.Field<String> PM_TYPE = field(name("type"), String.class);
    static final org.jooq.Field<String> PM_CARD_BRAND = field(name("card_brand"), String.class);
    static final org.jooq.Field<String> PM_CARD_LAST4 = field(name("card_last4"), String.class);
    static final org.jooq.Field<Short> PM_CARD_EXP_MONTH =
            field(name("card_exp_month"), Short.class);
    static final org.jooq.Field<Short> PM_CARD_EXP_YEAR = field(name("card_exp_year"), Short.class);
    static final org.jooq.Field<Boolean> PM_IS_DEFAULT = field(name("is_default"), Boolean.class);
    static final org.jooq.Field<String> PM_STATUS = field(name("status"), String.class);
    static final org.jooq.Field<OffsetDateTime> PM_CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    static final org.jooq.Field<OffsetDateTime> PM_UPDATED_AT =
            field(name("updated_at"), OffsetDateTime.class);

    // -- invoice table --------------------------------------------------------
    static final org.jooq.Table<?> INVOICE = table(name("cveilbilling", "invoice"));
    static final org.jooq.Field<UUID> INV_ID = field(name("id"), UUID.class);
    static final org.jooq.Field<String> INV_TENANT_ID = field(name("tenant_id"), String.class);
    static final org.jooq.Field<String> INV_STRIPE_INVOICE_ID =
            field(name("stripe_invoice_id"), String.class);
    static final org.jooq.Field<String> INV_STRIPE_SUBSCRIPTION_ID =
            field(name("stripe_subscription_id"), String.class);
    static final org.jooq.Field<String> INV_STATUS = field(name("status"), String.class);
    static final org.jooq.Field<String> INV_CURRENCY = field(name("currency"), String.class);
    static final org.jooq.Field<Long> INV_AMOUNT_DUE = field(name("amount_due"), Long.class);
    static final org.jooq.Field<Long> INV_AMOUNT_PAID = field(name("amount_paid"), Long.class);
    static final org.jooq.Field<Long> INV_AMOUNT_REMAINING =
            field(name("amount_remaining"), Long.class);
    static final org.jooq.Field<Long> INV_SUBTOTAL = field(name("subtotal"), Long.class);
    static final org.jooq.Field<Long> INV_TAX = field(name("tax"), Long.class);
    static final org.jooq.Field<Long> INV_TOTAL = field(name("total"), Long.class);
    static final org.jooq.Field<String> INV_HOSTED_URL =
            field(name("hosted_invoice_url"), String.class);
    static final org.jooq.Field<String> INV_PDF_URL = field(name("invoice_pdf_url"), String.class);
    static final org.jooq.Field<OffsetDateTime> INV_PERIOD_START =
            field(name("period_start"), OffsetDateTime.class);
    static final org.jooq.Field<OffsetDateTime> INV_PERIOD_END =
            field(name("period_end"), OffsetDateTime.class);
    static final org.jooq.Field<OffsetDateTime> INV_DUE_DATE =
            field(name("due_date"), OffsetDateTime.class);
    static final org.jooq.Field<OffsetDateTime> INV_PAID_AT =
            field(name("paid_at"), OffsetDateTime.class);
    static final org.jooq.Field<OffsetDateTime> INV_CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);
    static final org.jooq.Field<OffsetDateTime> INV_UPDATED_AT =
            field(name("updated_at"), OffsetDateTime.class);

    // -- invoice_line_item table ----------------------------------------------
    static final org.jooq.Table<?> INVOICE_LINE_ITEM =
            table(name("cveilbilling", "invoice_line_item"));
    static final org.jooq.Field<UUID> ILI_ID = field(name("id"), UUID.class);
    static final org.jooq.Field<UUID> ILI_INVOICE_ID = field(name("invoice_id"), UUID.class);
    static final org.jooq.Field<String> ILI_STRIPE_LINE_ITEM_ID =
            field(name("stripe_line_item_id"), String.class);
    static final org.jooq.Field<String> ILI_DESCRIPTION = field(name("description"), String.class);
    static final org.jooq.Field<Long> ILI_AMOUNT = field(name("amount"), Long.class);
    static final org.jooq.Field<String> ILI_CURRENCY = field(name("currency"), String.class);
    static final org.jooq.Field<Integer> ILI_QUANTITY = field(name("quantity"), Integer.class);
    static final org.jooq.Field<Long> ILI_UNIT_AMOUNT = field(name("unit_amount"), Long.class);
    static final org.jooq.Field<OffsetDateTime> ILI_PERIOD_START =
            field(name("period_start"), OffsetDateTime.class);
    static final org.jooq.Field<OffsetDateTime> ILI_PERIOD_END =
            field(name("period_end"), OffsetDateTime.class);
    static final org.jooq.Field<Boolean> ILI_PRORATION = field(name("proration"), Boolean.class);
    static final org.jooq.Field<OffsetDateTime> ILI_CREATED_AT =
            field(name("created_at"), OffsetDateTime.class);

    private BillingRecordMapper() {}

    static BillingProfileInfo toProfileInfo(Record r) {
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

    static SubscriptionInfo toSubscriptionInfo(Record r) {
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

    static PaymentMethodInfo toPaymentMethodInfo(Record r) {
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

    static InvoiceInfo toInvoiceInfo(Record r) {
        return toInvoiceInfo(r, List.of());
    }

    static InvoiceInfo toInvoiceInfo(Record r, List<InvoiceLineItemInfo> lineItems) {
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

    static InvoiceLineItemInfo toLineItemInfo(Record r) {
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

    static int safeInt(Long value) {
        return value != null ? value.intValue() : 0;
    }

    static long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    static int safeShort(Short value) {
        return value != null ? value.intValue() : 0;
    }

    static String safeUuid(UUID value) {
        return value != null ? value.toString() : null;
    }

    static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }
}
