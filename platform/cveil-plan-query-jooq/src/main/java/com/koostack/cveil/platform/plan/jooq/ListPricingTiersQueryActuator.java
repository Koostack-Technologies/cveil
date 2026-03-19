package com.koostack.cveil.platform.plan.jooq;

import static org.jooq.impl.DSL.*;

import com.koostack.cveil.core.cqrs.QueryActuator;
import com.koostack.cveil.core.metrics.MetricPublisherService;
import com.koostack.cveil.core.platform.plan.PlanDisplayDetail;
import com.koostack.cveil.core.platform.plan.PlanPricingTier;
import com.koostack.cveil.core.platform.plan.PlanQueryService.PlanInfo;
import com.koostack.cveil.core.query.RetryableQuery;
import com.koostack.cveil.core.query.RetryableQuery.RetryConfig;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Query actuator that lists active, public plans joined with their display detail for the pricing
 * page.
 */
public class ListPricingTiersQueryActuator
        implements QueryActuator<
                ListPricingTiersQueryActuator.ListPricingTiersQuery, List<PlanPricingTier>> {

    // -- plan table fields ----------------------------------------------------

    private static final org.jooq.Table<?> PLAN = table(name("cveilcore", "plan"));
    private static final org.jooq.Field<String> P_PLAN_ID =
            field(name("cveilcore", "plan", "plan_id"), String.class);
    private static final org.jooq.Field<String> DISPLAY_NAME =
            field(name("cveilcore", "plan", "display_name"), String.class);
    private static final org.jooq.Field<String> P_DESCRIPTION =
            field(name("cveilcore", "plan", "description"), String.class);
    private static final org.jooq.Field<Long> EVAL_LIMIT =
            field(name("cveilcore", "plan", "eval_limit"), Long.class);
    private static final org.jooq.Field<Long> SUBJECT_LIMIT =
            field(name("cveilcore", "plan", "subject_limit"), Long.class);
    private static final org.jooq.Field<Integer> BURST_LIMIT =
            field(name("cveilcore", "plan", "burst_limit"), Integer.class);
    private static final org.jooq.Field<Integer> JURISDICTION_LIMIT =
            field(name("cveilcore", "plan", "jurisdiction_limit"), Integer.class);
    private static final org.jooq.Field<Boolean> IS_PUBLIC =
            field(name("cveilcore", "plan", "is_public"), Boolean.class);
    private static final org.jooq.Field<Boolean> IS_ACTIVE =
            field(name("cveilcore", "plan", "is_active"), Boolean.class);
    private static final org.jooq.Field<Integer> SORT_ORDER =
            field(name("cveilcore", "plan", "sort_order"), Integer.class);
    private static final org.jooq.Field<String> STRIPE_PRODUCT_ID =
            field(name("cveilcore", "plan", "stripe_product_id"), String.class);
    private static final org.jooq.Field<String> STRIPE_PRICE_ID =
            field(name("cveilcore", "plan", "stripe_price_id"), String.class);
    private static final org.jooq.Field<Integer> MEMBER_LIMIT =
            field(name("cveilcore", "plan", "member_limit"), Integer.class);
    private static final org.jooq.Field<Integer> AUDIT_RETENTION_DAYS =
            field(name("cveilcore", "plan", "audit_retention_days"), Integer.class);
    private static final org.jooq.Field<String> STRIPE_ANNUAL_PRICE_ID =
            field(name("cveilcore", "plan", "stripe_annual_price_id"), String.class);

    // -- plan_display_detail table fields -------------------------------------

    private static final org.jooq.Table<?> DETAIL = table(name("cveilcore", "plan_display_detail"));
    private static final org.jooq.Field<String> D_PLAN_ID =
            field(name("cveilcore", "plan_display_detail", "plan_id"), String.class);
    private static final org.jooq.Field<String> PRICE =
            field(name("cveilcore", "plan_display_detail", "price"), String.class);
    private static final org.jooq.Field<String> PERIOD =
            field(name("cveilcore", "plan_display_detail", "period"), String.class);
    private static final org.jooq.Field<String> OVERAGE =
            field(name("cveilcore", "plan_display_detail", "overage"), String.class);
    private static final org.jooq.Field<String> GOVERNANCE =
            field(name("cveilcore", "plan_display_detail", "governance"), String.class);
    private static final org.jooq.Field<String> SUPPORT =
            field(name("cveilcore", "plan_display_detail", "support"), String.class);
    private static final org.jooq.Field<String> RESIDENCY =
            field(name("cveilcore", "plan_display_detail", "residency"), String.class);
    private static final org.jooq.Field<String> CTA =
            field(name("cveilcore", "plan_display_detail", "cta"), String.class);
    private static final org.jooq.Field<Boolean> HIGHLIGHTED =
            field(name("cveilcore", "plan_display_detail", "highlighted"), Boolean.class);

    private final DSLContext dsl;
    private final RetryConfig retryConfig;

    public ListPricingTiersQueryActuator(DSLContext dsl, RetryConfig retryConfig) {
        this.dsl = dsl;
        this.retryConfig = retryConfig;
    }

    /** Query input — no parameters needed (lists all active public tiers). */
    public record ListPricingTiersQuery() {}

    @Override
    public List<PlanPricingTier> execute(ListPricingTiersQuery query) {
        return RetryableQuery.withRetryCollection(
                () ->
                        dsl.select()
                                .from(PLAN)
                                .join(DETAIL)
                                .on(P_PLAN_ID.eq(D_PLAN_ID))
                                .where(IS_ACTIVE.eq(true))
                                .and(IS_PUBLIC.eq(true))
                                .orderBy(SORT_ORDER.asc())
                                .fetch(this::toPricingTier),
                retryConfig,
                "listPricingTiers");
    }

    @Override
    public MetricPublisherService<?> metricsPublisher() {
        return null;
    }

    private PlanPricingTier toPricingTier(Record record) {
        PlanInfo plan =
                new PlanInfo(
                        record.get(P_PLAN_ID),
                        record.get(DISPLAY_NAME),
                        record.get(P_DESCRIPTION),
                        record.get(EVAL_LIMIT),
                        record.get(SUBJECT_LIMIT),
                        record.get(BURST_LIMIT),
                        record.get(JURISDICTION_LIMIT) != null ? record.get(JURISDICTION_LIMIT) : 3,
                        record.get(MEMBER_LIMIT) != null ? record.get(MEMBER_LIMIT) : -1,
                        record.get(AUDIT_RETENTION_DAYS) != null
                                ? record.get(AUDIT_RETENTION_DAYS)
                                : 30,
                        record.get(IS_PUBLIC),
                        record.get(IS_ACTIVE),
                        record.get(SORT_ORDER),
                        record.get(STRIPE_PRODUCT_ID),
                        record.get(STRIPE_PRICE_ID),
                        record.get(STRIPE_ANNUAL_PRICE_ID));

        PlanDisplayDetail detail =
                new PlanDisplayDetail(
                        record.get(D_PLAN_ID),
                        record.get(PRICE),
                        record.get(PERIOD),
                        record.get(OVERAGE),
                        record.get(GOVERNANCE),
                        record.get(SUPPORT),
                        record.get(RESIDENCY),
                        record.get(CTA),
                        record.get(HIGHLIGHTED));

        return PlanPricingTier.of(plan, detail);
    }
}
