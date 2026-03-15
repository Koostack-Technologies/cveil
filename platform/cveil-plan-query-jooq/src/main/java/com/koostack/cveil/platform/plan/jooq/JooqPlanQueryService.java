package com.koostack.cveil.platform.plan.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.platform.plan.PlanQueryService;
import com.koostack.cveil.core.query.RetryableQuery;
import com.koostack.cveil.core.query.RetryableQuery.RetryConfig;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqPlanQueryService implements PlanQueryService<JooqPlanQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqPlanQueryService.class);

    private static final org.jooq.Table<?> PLAN = table(name("platform_core", "plan"));
    private static final org.jooq.Field<String> PLAN_ID = field(name("plan_id"), String.class);
    private static final org.jooq.Field<String> DISPLAY_NAME =
            field(name("display_name"), String.class);
    private static final org.jooq.Field<String> DESCRIPTION =
            field(name("description"), String.class);
    private static final org.jooq.Field<Long> EVAL_LIMIT = field(name("eval_limit"), Long.class);
    private static final org.jooq.Field<Long> SUBJECT_LIMIT =
            field(name("subject_limit"), Long.class);
    private static final org.jooq.Field<Integer> BURST_LIMIT =
            field(name("burst_limit"), Integer.class);
    private static final org.jooq.Field<Boolean> IS_PUBLIC =
            field(name("is_public"), Boolean.class);
    private static final org.jooq.Field<Boolean> IS_ACTIVE =
            field(name("is_active"), Boolean.class);
    private static final org.jooq.Field<Integer> SORT_ORDER =
            field(name("sort_order"), Integer.class);
    private static final org.jooq.Field<String> STRIPE_PRODUCT_ID =
            field(name("stripe_product_id"), String.class);
    private static final org.jooq.Field<String> STRIPE_PRICE_ID =
            field(name("stripe_price_id"), String.class);

    private DSLContext dsl;
    private RetryConfig retryConfig = RetryableQuery.DEFAULT;

    @Override
    public String getName() {
        return "jooq-plan-query";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public Class<JooqPlanQueryConfig> getConfigurationType() {
        return JooqPlanQueryConfig.class;
    }

    @Override
    public void configure(JooqPlanQueryConfig config) {
        this.dsl = config.dsl();
        LOG.info("JooqPlanQueryService configured");
    }

    @Override
    public boolean isReady() {
        return dsl != null;
    }

    public void setRetryConfig(RetryConfig retryConfig) {
        this.retryConfig = retryConfig;
    }

    @Override
    public PlanInfo getPlan(String planId) {
        return RetryableQuery.withRetry(
                () -> {
                    Record record = dsl.select().from(PLAN).where(PLAN_ID.eq(planId)).fetchOne();
                    return record != null ? toPlanInfo(record) : null;
                },
                result -> result == null,
                retryConfig,
                "getPlan(" + planId + ")");
    }

    @Override
    public List<PlanInfo> listPlans() {
        return RetryableQuery.withRetryCollection(
                () -> dsl.select().from(PLAN).orderBy(SORT_ORDER.asc()).fetch(this::toPlanInfo),
                retryConfig,
                "listPlans");
    }

    @Override
    public List<PlanInfo> listActivePlans() {
        return RetryableQuery.withRetryCollection(
                () ->
                        dsl.select()
                                .from(PLAN)
                                .where(IS_ACTIVE.eq(true))
                                .orderBy(SORT_ORDER.asc())
                                .fetch(this::toPlanInfo),
                retryConfig,
                "listActivePlans");
    }

    private PlanInfo toPlanInfo(Record record) {
        return new PlanInfo(
                record.get(PLAN_ID),
                record.get(DISPLAY_NAME),
                record.get(DESCRIPTION),
                record.get(EVAL_LIMIT),
                record.get(SUBJECT_LIMIT),
                record.get(BURST_LIMIT),
                record.get(IS_PUBLIC),
                record.get(IS_ACTIVE),
                record.get(SORT_ORDER),
                record.get(STRIPE_PRODUCT_ID),
                record.get(STRIPE_PRICE_ID));
    }
}
