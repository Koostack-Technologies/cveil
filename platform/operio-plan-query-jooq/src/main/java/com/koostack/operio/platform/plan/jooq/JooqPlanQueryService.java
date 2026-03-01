package com.koostack.operio.platform.plan.jooq;

import com.koostack.operio.core.platform.plan.PlanQueryService;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.jooq.impl.DSL.*;

public class JooqPlanQueryService implements PlanQueryService<JooqPlanQueryConfig> {

    private static final Logger LOG = LoggerFactory.getLogger(JooqPlanQueryService.class);

    private static final org.jooq.Table<?> PLAN = table(name("platform_core", "plan"));
    private static final org.jooq.Field<String> PLAN_ID = field(name("plan_id"), String.class);
    private static final org.jooq.Field<String> DISPLAY_NAME = field(name("display_name"), String.class);
    private static final org.jooq.Field<String> DESCRIPTION = field(name("description"), String.class);
    private static final org.jooq.Field<Long> EVAL_LIMIT = field(name("eval_limit"), Long.class);
    private static final org.jooq.Field<Long> SUBJECT_LIMIT = field(name("subject_limit"), Long.class);
    private static final org.jooq.Field<Integer> BURST_LIMIT = field(name("burst_limit"), Integer.class);
    private static final org.jooq.Field<Boolean> IS_PUBLIC = field(name("is_public"), Boolean.class);
    private static final org.jooq.Field<Boolean> IS_ACTIVE = field(name("is_active"), Boolean.class);
    private static final org.jooq.Field<Integer> SORT_ORDER = field(name("sort_order"), Integer.class);

    private DSLContext dsl;

    @Override public String getName() { return "jooq-plan-query"; }
    @Override public int getPriority() { return 100; }
    @Override public Class<JooqPlanQueryConfig> getConfigurationType() { return JooqPlanQueryConfig.class; }

    @Override
    public void configure(JooqPlanQueryConfig config) {
        this.dsl = config.dsl();
        LOG.info("JooqPlanQueryService configured");
    }

    @Override
    public boolean isReady() {
        return dsl != null;
    }

    @Override
    public PlanInfo getPlan(String planId) {
        Record record = dsl.select()
            .from(PLAN)
            .where(PLAN_ID.eq(planId))
            .fetchOne();
        return record != null ? toPlanInfo(record) : null;
    }

    @Override
    public List<PlanInfo> listPlans() {
        return dsl.select()
            .from(PLAN)
            .orderBy(SORT_ORDER.asc())
            .fetch(this::toPlanInfo);
    }

    @Override
    public List<PlanInfo> listActivePlans() {
        return dsl.select()
            .from(PLAN)
            .where(IS_ACTIVE.eq(true))
            .orderBy(SORT_ORDER.asc())
            .fetch(this::toPlanInfo);
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
            record.get(SORT_ORDER)
        );
    }
}
