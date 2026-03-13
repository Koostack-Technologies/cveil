package com.koostack.operio.platform.plan.jooq;

import org.jooq.DSLContext;

/**
 * Configuration for {@link JooqPlanQueryService}.
 *
 * @param dsl the {@link DSLContext} targeting the platform database
 */
public record JooqPlanQueryConfig(DSLContext dsl) {
    public JooqPlanQueryConfig {
        if (dsl == null) {
            throw new IllegalArgumentException("dsl must not be null");
        }
    }
}
