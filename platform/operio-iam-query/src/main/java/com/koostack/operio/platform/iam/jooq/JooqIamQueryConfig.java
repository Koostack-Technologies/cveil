package com.koostack.operio.platform.iam.jooq;

import org.jooq.DSLContext;

/**
 * Configuration for {@link JooqIamQueryService}.
 *
 * @param dsl the {@link DSLContext} targeting the platform database
 */
public record JooqIamQueryConfig(DSLContext dsl) {
    public JooqIamQueryConfig {
        if (dsl == null) {
            throw new IllegalArgumentException("dsl must not be null");
        }
    }
}
