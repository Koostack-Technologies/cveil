package com.koostack.operio.platform.billing.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.operio.core.platform.billing.models.BillingProfileInfo;
import com.koostack.operio.core.platform.billing.models.SubscriptionInfo;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.jooq.Record;

/**
 * Shared jOOQ field constants and record-to-domain mapping utilities for billing queries.
 */
public final class BillingRecordMapper {

    // -- billing_profile table ------------------------------------------------
    static final org.jooq.Table<?> BILLING_PROFILE =
            table(name("platform_billing", "billing_profile"));
    static final org.jooq.Field<String> BP_TENANT_ID =
            field(name("tenant_id"), String.class);
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
    static final org.jooq.Table<?> SUBSCRIPTION =
            table(name("platform_billing", "subscription"));
    static final org.jooq.Field<String> SUB_TENANT_ID =
            field(name("tenant_id"), String.class);
    static final org.jooq.Field<String> SUB_PLAN_TIER =
            field(name("plan_tier"), String.class);
    static final org.jooq.Field<Long> SUB_EVAL_LIMIT =
            field(name("eval_limit_monthly"), Long.class);
    static final org.jooq.Field<Long> SUB_SUBJECT_LIMIT =
            field(name("subject_limit"), Long.class);
    static final org.jooq.Field<Integer> SUB_BURST_LIMIT =
            field(name("burst_limit_per_min"), Integer.class);
    static final org.jooq.Field<String> SUB_STATUS = field(name("status"), String.class);
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
                toInstant(r.get(SUB_PERIOD_START)),
                toInstant(r.get(SUB_PERIOD_END)),
                Boolean.TRUE.equals(r.get(SUB_CANCEL_AT_PERIOD_END)),
                toInstant(r.get(SUB_CREATED_AT)),
                toInstant(r.get(SUB_UPDATED_AT)));
    }

    static int safeInt(Long value) {
        return value != null ? value.intValue() : 0;
    }

    static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }
}
