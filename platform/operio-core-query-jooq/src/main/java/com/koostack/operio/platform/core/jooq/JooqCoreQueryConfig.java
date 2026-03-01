package com.koostack.operio.platform.core.jooq;

import org.jooq.DSLContext;

/**
 * Configuration for {@link JooqCoreQueryService}.
 *
 * @param dsl the platform {@link DSLContext} targeting the platform_core schema
 */
public record JooqCoreQueryConfig(DSLContext dsl) {
    public JooqCoreQueryConfig {
        if (dsl == null) {
            throw new IllegalArgumentException("dsl must not be null");
        }
    }
}
