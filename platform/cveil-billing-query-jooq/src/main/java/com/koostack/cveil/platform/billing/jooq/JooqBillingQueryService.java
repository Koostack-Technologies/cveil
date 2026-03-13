package com.koostack.cveil.platform.billing.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.platform.billing.BillingQueryService;
import com.koostack.cveil.core.platform.billing.models.BillingProfileInfo;
import com.koostack.cveil.core.platform.billing.models.SubscriptionInfo;
import com.koostack.cveil.core.query.RetryableQuery;
import com.koostack.cveil.core.query.RetryableQuery.RetryConfig;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqBillingQueryService implements BillingQueryService<JooqBillingQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqBillingQueryService.class);

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
                toInstant(r.get(SUB_PERIOD_START)),
                toInstant(r.get(SUB_PERIOD_END)),
                Boolean.TRUE.equals(r.get(SUB_CANCEL_AT_PERIOD_END)),
                toInstant(r.get(SUB_CREATED_AT)),
                toInstant(r.get(SUB_UPDATED_AT)));
    }

    private static int safeInt(Long value) {
        return value != null ? value.intValue() : 0;
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt != null ? odt.toInstant() : null;
    }
}
